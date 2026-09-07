/*
 * Copyright (C) 2026 The Evolution X Project
 * SPDX-License-Identifier: Apache-2.0
 *
 * Minimal recovery-side FBE unlock helper for Xiaomi haotian.
 *
 * This intentionally supports the Gatekeeper Synthetic Password path used by
 * the current Evolution X build only.  It never enrolls credentials, creates
 * keys, fixes up key directories, or implements Weaver.
 */

#include <android-base/file.h>
#include <android-base/logging.h>
#include <android-base/properties.h>
#include <android-base/strings.h>
#include <android-base/unique_fd.h>
#include <cutils/sockets.h>
#include <fcntl.h>
#include <libdm/dm.h>
#include <libdm/dm_target.h>
#include <linux/fs.h>
#include <linux/fscrypt.h>
#include <openssl/aead.h>
#include <openssl/evp.h>
#include <openssl/hmac.h>
#include <openssl/mem.h>
#include <openssl/rand.h>
#include <openssl/sha.h>
#include <openssl/x509.h>
#include <poll.h>
#include <sqlite3.h>
#include <sys/ioctl.h>
#include <sys/mount.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <termios.h>
#include <unistd.h>

#include <aidl/android/hardware/gatekeeper/GatekeeperVerifyResponse.h>
#include <aidl/android/hardware/gatekeeper/IGatekeeper.h>
#include <aidl/android/hardware/security/keymint/BeginResult.h>
#include <aidl/android/hardware/security/keymint/ErrorCode.h>
#include <aidl/android/hardware/security/keymint/HardwareAuthToken.h>
#include <aidl/android/hardware/security/keymint/IKeyMintDevice.h>
#include <aidl/android/hardware/security/keymint/KeyMintHardwareInfo.h>
#include <aidl/android/hardware/security/sharedsecret/ISharedSecret.h>
#include <aidl/android/hardware/security/sharedsecret/SharedSecretParameters.h>
#include <android/binder_manager.h>
#include <android/binder_process.h>
#include <keymint_support/authorization_set.h>
#include <otautil/verifier.h>

#include <algorithm>
#include <array>
#include <cerrno>
#include <charconv>
#include <chrono>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <dirent.h>
#include <iomanip>
#include <iostream>
#include <limits>
#include <memory>
#include <optional>
#include <set>
#include <sstream>
#include <string>
#include <string_view>
#include <vector>

extern "C" {
#include "crypto_scrypt.h"
}

namespace {

namespace km = aidl::android::hardware::security::keymint;
using android::base::unique_fd;
using android::dm::DeviceMapper;
using android::dm::DmDeviceState;
using android::dm::DmTable;
using android::dm::DmTargetDefaultKey;
using namespace std::chrono_literals;

constexpr char kSocketName[] = "haotian_recovery_ce";
constexpr char kOtaCertificates[] = "/system/etc/security/otacerts.zip";
constexpr char kAuthFailureState[] =
    "/metadata/ota/haotian_recovery_adb_auth_failures";
constexpr char kLocksettingsDb[] = "/data/system/locksettings.db";
constexpr char kKeystoreDb[] = "/data/misc/keystore/persistent.sqlite";
constexpr char kGatekeeperService[] =
    "android.hardware.gatekeeper.IGatekeeper/default";
constexpr char kKeyMintService[] =
    "android.hardware.security.keymint.IKeyMintDevice/default";
constexpr char kSharedSecretService[] =
    "android.hardware.security.sharedsecret.ISharedSecret/default";
constexpr char kUserdataBlock[] = "/dev/block/bootdevice/by-name/userdata";
constexpr char kMetadataBlock[] = "/dev/block/by-name/metadata";
constexpr char kMetadataKeyDir[] = "/metadata/vold/metadata_encryption/key";
constexpr char kSystemDeKeyDir[] = "/data/unencrypted/key";
constexpr char kUserDeKeyRoot[] = "/data/misc/vold/user_keys/de";
constexpr char kUserCeKeyRoot[] = "/data/misc/vold/user_keys/ce";
constexpr char kSystemDeRoot[] = "/data/system_de";
constexpr char kSystemUsersRoot[] = "/data/system/users";
constexpr int32_t kFakeUserIdBase = 100000;
constexpr uint32_t kMaxAndroidUserId =
    static_cast<uint32_t>(std::numeric_limits<int32_t>::max() -
                          kFakeUserIdBase);
constexpr size_t kMaxCredentialBytes = 4096;
constexpr size_t kMaintenanceSecretBytes = 32;
constexpr auto kMaintenanceChallengeLifetime = 5min;

constexpr int32_t kCredentialNone = -1;
constexpr int32_t kCredentialPattern = 1;
constexpr int32_t kCredentialPin = 3;
constexpr int32_t kCredentialPassword = 4;

enum class RequestKind : uint32_t {
  kStatus = 0,
  kNone = 1,
  kPattern = 2,
  kPin = 3,
  kPassword = 4,
  kMaintenanceChallenge = 5,
  kMaintenanceResponse = 6,
};

struct RequestHeader {
  uint32_t magic;
  uint32_t kind;
  uint32_t grid_size;
  uint32_t user_id;
  uint32_t credential_size;
};

constexpr uint32_t kRequestMagic = 0x48434531; // "HCE1"

struct PasswordData {
  int32_t credential_type = 0;
  uint8_t log_n = 0;
  uint8_t log_r = 0;
  uint8_t log_p = 0;
  std::vector<uint8_t> salt;
  std::vector<uint8_t> password_handle;
};

struct Protector {
  uint64_t id = 0;
  uint8_t version = 0;
  std::vector<uint8_t> content;
  std::optional<PasswordData> password_data;
  std::vector<uint8_t> secdiscardable;
};

void Zeroize(std::vector<uint8_t> *value) {
  if (!value->empty())
    OPENSSL_cleanse(value->data(), value->size());
  value->clear();
}

class ScopedZeroize {
public:
  explicit ScopedZeroize(std::vector<uint8_t> *value) : value_(value) {}
  ~ScopedZeroize() { Zeroize(value_); }

  ScopedZeroize(const ScopedZeroize &) = delete;
  ScopedZeroize &operator=(const ScopedZeroize &) = delete;

private:
  std::vector<uint8_t> *value_;
};

bool ReadExact(int fd, void *data, size_t size) {
  auto *p = static_cast<uint8_t *>(data);
  while (size != 0) {
    ssize_t n = TEMP_FAILURE_RETRY(read(fd, p, size));
    if (n <= 0)
      return false;
    p += n;
    size -= static_cast<size_t>(n);
  }
  return true;
}

bool WriteExact(int fd, const void *data, size_t size) {
  const auto *p = static_cast<const uint8_t *>(data);
  while (size != 0) {
    ssize_t n = TEMP_FAILURE_RETRY(write(fd, p, size));
    if (n <= 0)
      return false;
    p += n;
    size -= static_cast<size_t>(n);
  }
  return true;
}

bool SendString(int fd, const std::string &value) {
  if (value.size() > std::numeric_limits<uint32_t>::max())
    return false;
  const uint32_t size = static_cast<uint32_t>(value.size());
  return WriteExact(fd, &size, sizeof(size)) &&
         WriteExact(fd, value.data(), value.size());
}

std::optional<std::string> ReceiveString(int fd) {
  uint32_t size = 0;
  if (!ReadExact(fd, &size, sizeof(size)) || size > 64 * 1024)
    return std::nullopt;
  std::string value(size, '\0');
  if (!ReadExact(fd, value.data(), value.size()))
    return std::nullopt;
  return value;
}

uint32_t ReadBe32(const uint8_t *p) {
  return (static_cast<uint32_t>(p[0]) << 24) |
         (static_cast<uint32_t>(p[1]) << 16) |
         (static_cast<uint32_t>(p[2]) << 8) | p[3];
}

bool ReadFile(const std::string &path, std::vector<uint8_t> *out) {
  std::string data;
  if (!android::base::ReadFileToString(path, &data))
    return false;
  out->assign(data.begin(), data.end());
  return true;
}

bool IsValidUserId(uint32_t user_id) { return user_id <= kMaxAndroidUserId; }

std::string UserSpDir(uint32_t user_id) {
  return std::string(kSystemDeRoot) + '/' + std::to_string(user_id) +
         "/spblob";
}

std::string UserDeKeyDir(uint32_t user_id) {
  return std::string(kUserDeKeyRoot) + '/' + std::to_string(user_id);
}

std::string UserCeKeyDir(uint32_t user_id) {
  return std::string(kUserCeKeyRoot) + '/' + std::to_string(user_id) +
         "/current";
}

std::string UnifiedProfileKeyFile(uint32_t user_id) {
  if (user_id == 0)
    return "/data/system/gatekeeper.profile.key";
  return std::string(kSystemUsersRoot) + '/' + std::to_string(user_id) +
         "/gatekeeper.profile.key";
}

std::vector<uint32_t> ListNumericDirectories(const char *root) {
  std::vector<uint32_t> result;
  DIR *directory = opendir(root);
  if (!directory)
    return result;
  while (dirent *entry = readdir(directory)) {
    const std::string_view name(entry->d_name);
    if (name.empty() || name == "." || name == "..")
      continue;
    uint32_t user_id = 0;
    const auto parsed =
        std::from_chars(name.data(), name.data() + name.size(), user_id, 10);
    if (parsed.ec != std::errc() || parsed.ptr != name.data() + name.size() ||
        !IsValidUserId(user_id)) {
      continue;
    }
    struct stat status {};
    const std::string path = std::string(root) + '/' + std::string(name);
    if (stat(path.c_str(), &status) == 0 && S_ISDIR(status.st_mode))
      result.push_back(user_id);
  }
  closedir(directory);
  std::sort(result.begin(), result.end());
  result.erase(std::unique(result.begin(), result.end()), result.end());
  return result;
}

std::vector<uint32_t> ListUsers() {
  std::set<uint32_t> users;
  for (uint32_t user_id : ListNumericDirectories(kSystemDeRoot))
    users.insert(user_id);
  for (uint32_t user_id : ListNumericDirectories(kUserDeKeyRoot))
    users.insert(user_id);
  return std::vector<uint32_t>(users.begin(), users.end());
}

std::vector<uint8_t> PersonalizedHash(std::string_view personalization,
                                      const std::vector<uint8_t> &message) {
  std::array<uint8_t, 128> prefix{};
  if (personalization.size() > prefix.size())
    return {};
  memcpy(prefix.data(), personalization.data(), personalization.size());
  std::vector<uint8_t> digest(SHA512_DIGEST_LENGTH);
  SHA512_CTX context;
  SHA512_Init(&context);
  SHA512_Update(&context, prefix.data(), prefix.size());
  SHA512_Update(&context, message.data(), message.size());
  SHA512_Final(digest.data(), &context);
  return digest;
}

std::vector<uint8_t> AesGcmDecrypt(const std::vector<uint8_t> &key,
                                   const std::vector<uint8_t> &blob) {
  constexpr size_t kIvSize = 12;
  constexpr size_t kTagSize = 16;
  if (key.size() != 32 || blob.size() < kIvSize + kTagSize)
    return {};
  EVP_AEAD_CTX context;
  if (!EVP_AEAD_CTX_init(&context, EVP_aead_aes_256_gcm(), key.data(),
                         key.size(), kTagSize, nullptr)) {
    return {};
  }
  std::vector<uint8_t> plaintext(blob.size() - kIvSize);
  size_t plaintext_size = 0;
  const bool ok = EVP_AEAD_CTX_open(&context, plaintext.data(), &plaintext_size,
                                    plaintext.size(), blob.data(), kIvSize,
                                    blob.data() + kIvSize,
                                    blob.size() - kIvSize, nullptr, 0);
  EVP_AEAD_CTX_cleanup(&context);
  if (!ok) {
    Zeroize(&plaintext);
    return {};
  }
  plaintext.resize(plaintext_size);
  return plaintext;
}

std::optional<PasswordData> ParsePasswordData(const std::vector<uint8_t> &raw) {
  if (raw.size() < 15)
    return std::nullopt;
  size_t at = 0;
  auto read_u32 = [&]() -> std::optional<uint32_t> {
    if (raw.size() - at < 4)
      return std::nullopt;
    uint32_t value = ReadBe32(raw.data() + at);
    at += 4;
    return value;
  };
  PasswordData result;
  auto type = read_u32();
  if (!type)
    return std::nullopt;
  result.credential_type = static_cast<int16_t>(*type);
  result.log_n = raw[at++];
  result.log_r = raw[at++];
  result.log_p = raw[at++];
  auto salt_size = read_u32();
  if (!salt_size || *salt_size > raw.size() - at)
    return std::nullopt;
  result.salt.assign(raw.begin() + at, raw.begin() + at + *salt_size);
  at += *salt_size;
  auto handle_size = read_u32();
  if (!handle_size || *handle_size > raw.size() - at)
    return std::nullopt;
  result.password_handle.assign(raw.begin() + at,
                                raw.begin() + at + *handle_size);
  return result;
}

bool ParseProtectorId(std::string_view filename, uint64_t *id) {
  constexpr std::string_view suffix = ".spblob";
  if (filename.size() != 16 + suffix.size() || !filename.ends_with(suffix))
    return false;
  auto hex = filename.substr(0, 16);
  auto result = std::from_chars(hex.data(), hex.data() + hex.size(), *id, 16);
  return result.ec == std::errc() && result.ptr == hex.data() + hex.size();
}

std::optional<std::string> ReadLocksetting(uint32_t user_id,
                                           const char *name) {
  sqlite3 *db = nullptr;
  if (sqlite3_open_v2(kLocksettingsDb, &db,
                      SQLITE_OPEN_READONLY | SQLITE_OPEN_FULLMUTEX,
                      nullptr) != SQLITE_OK) {
    if (db)
      sqlite3_close(db);
    return std::nullopt;
  }
  sqlite3_stmt *statement = nullptr;
  constexpr char query[] =
      "SELECT value FROM locksettings WHERE user=? AND name=? LIMIT 1";
  std::optional<std::string> result;
  if (sqlite3_prepare_v2(db, query, -1, &statement, nullptr) == SQLITE_OK &&
      sqlite3_bind_int64(statement, 1, user_id) == SQLITE_OK &&
      sqlite3_bind_text(statement, 2, name, -1, SQLITE_STATIC) == SQLITE_OK &&
      sqlite3_step(statement) == SQLITE_ROW) {
    const unsigned char *text = sqlite3_column_text(statement, 0);
    if (text)
      result = reinterpret_cast<const char *>(text);
  }
  if (statement)
    sqlite3_finalize(statement);
  sqlite3_close(db);
  return result;
}

std::optional<Protector> LoadProtector(uint32_t user_id, std::string *error) {
  const std::string sp_dir = UserSpDir(user_id);
  DIR *directory = opendir(sp_dir.c_str());
  if (!directory) {
    *error = "cannot open Synthetic Password directory for user " +
             std::to_string(user_id) + " (DE is not mounted)";
    return std::nullopt;
  }
  std::vector<uint64_t> candidates;
  while (dirent *entry = readdir(directory)) {
    uint64_t id;
    if (ParseProtectorId(entry->d_name, &id))
      candidates.push_back(id);
  }
  closedir(directory);

  // During credential replacement an old protector can deliberately remain
  // until boot completes.  Always select the ID referenced by LockSettings,
  // never the first directory entry returned by readdir().
  auto current = ReadLocksetting(user_id, "sp-handle");
  if (!current) {
    *error = "current LSKF protector ID is missing from locksettings.db";
    return std::nullopt;
  }
  int64_t signed_current_id = 0;
  auto parsed =
      std::from_chars(current->data(), current->data() + current->size(),
                      signed_current_id, 10);
  if (parsed.ec != std::errc() ||
      parsed.ptr != current->data() + current->size()) {
    *error = "invalid current LSKF protector ID";
    return std::nullopt;
  }
  const uint64_t current_id = static_cast<uint64_t>(signed_current_id);

  for (uint64_t id : candidates) {
    if (id != current_id)
      continue;
    std::ostringstream stem;
    stem << sp_dir << '/' << std::hex << std::setw(16) << std::setfill('0')
         << id;
    std::vector<uint8_t> blob;
    if (!ReadFile(stem.str() + ".spblob", &blob) || blob.size() < 3 ||
        blob[1] != 0)
      continue;
    if (access((stem.str() + ".weaver").c_str(), F_OK) == 0) {
      *error = "protector uses Weaver; this recovery intentionally does not "
               "support it";
      return std::nullopt;
    }
    Protector protector;
    protector.id = id;
    protector.version = blob[0];
    protector.content.assign(blob.begin() + 2, blob.end());
    if (!ReadFile(stem.str() + ".secdis", &protector.secdiscardable)) {
      *error = "protector secdiscardable is missing";
      return std::nullopt;
    }
    std::vector<uint8_t> pwd;
    if (ReadFile(stem.str() + ".pwd", &pwd)) {
      protector.password_data = ParsePasswordData(pwd);
      Zeroize(&pwd);
      if (!protector.password_data) {
        *error = "invalid password-data file";
        return std::nullopt;
      }
    }
    return protector;
  }
  *error = "no current LSKF Synthetic Password protector found";
  return std::nullopt;
}

int ReadPatternGridSize(uint32_t user_id) {
  auto setting = ReadLocksetting(user_id, "lock_pattern_size");
  int result = setting ? atoi(setting->c_str()) : 0;
  return result >= 3 && result <= 6 ? result : 0;
}

std::optional<std::vector<uint8_t>>
EncodePattern(const std::vector<uint8_t> &input, uint32_t requested_grid,
              uint32_t user_id, std::string *error) {
  int grid = ReadPatternGridSize(user_id);
  if (grid != 0 && requested_grid != 0 &&
      grid != static_cast<int>(requested_grid)) {
    *error = "requested pattern grid does not match stored " +
             std::to_string(grid) + "x" + std::to_string(grid) + " grid";
    return std::nullopt;
  }
  if (grid == 0)
    grid = requested_grid;
  if (grid < 3 || grid > 6) {
    *error = "cannot determine pattern grid; specify 3, 4, 5, or 6";
    return std::nullopt;
  }
  std::string text(input.begin(), input.end());
  std::vector<uint8_t> result;
  std::array<bool, 36> used{};
  for (const std::string &token : android::base::Split(text, ",")) {
    int cell = -1;
    auto parsed =
        std::from_chars(token.data(), token.data() + token.size(), cell, 10);
    if (parsed.ec != std::errc() || parsed.ptr != token.data() + token.size() ||
        cell < 0 || cell >= grid * grid || used[cell]) {
      *error = "invalid or duplicate pattern cell for " + std::to_string(grid) +
               "x" + std::to_string(grid) + " grid";
      return std::nullopt;
    }
    used[cell] = true;
    // Same encoding as LockPatternUtils.patternToByteArray(pattern, gridSize).
    result.push_back(static_cast<uint8_t>(cell + '1'));
  }
  if (result.size() < 4) {
    *error = "pattern must contain at least four cells";
    return std::nullopt;
  }
  return result;
}

std::vector<uint8_t> StretchCredential(const std::vector<uint8_t> &credential,
                                       const std::optional<PasswordData> &pwd) {
  static constexpr char kDefaultPassword[] = "default-password";
  if (!pwd) {
    std::vector<uint8_t> result(32, 0);
    memcpy(result.data(), kDefaultPassword, sizeof(kDefaultPassword) - 1);
    return result;
  }
  const uint8_t *password = credential.data();
  size_t password_size = credential.size();
  // Old unsecured protectors kept PasswordData and scrypted DEFAULT_PASSWORD.
  // New unsecured protectors omit PasswordData and take the branch above.
  if (pwd->credential_type == kCredentialNone) {
    password = reinterpret_cast<const uint8_t *>(kDefaultPassword);
    password_size = sizeof(kDefaultPassword) - 1;
  }
  std::vector<uint8_t> result(32);
  if (pwd->log_n >= 31 || pwd->log_r >= 31 || pwd->log_p >= 31 ||
      crypto_scrypt(password, password_size, pwd->salt.data(), pwd->salt.size(),
                    uint64_t{1} << pwd->log_n, uint32_t{1} << pwd->log_r,
                    uint32_t{1} << pwd->log_p, result.data(),
                    result.size()) != 0) {
    Zeroize(&result);
  }
  return result;
}

std::shared_ptr<km::IKeyMintDevice> GetKeyMint(std::string *error) {
  ndk::SpAIBinder binder(AServiceManager_checkService(kKeyMintService));
  auto keymint = km::IKeyMintDevice::fromBinder(binder);
  if (!keymint)
    *error = "KeyMint HAL is unavailable";
  return keymint;
}

bool InitializeKeyMint(std::string *error) {
  static bool initialized = false;
  if (initialized)
    return true;

  auto keymint = GetKeyMint(error);
  if (!keymint)
    return false;
  km::KeyMintHardwareInfo hardware_info;
  auto status = keymint->getHardwareInfo(&hardware_info);
  if (!status.isOk()) {
    *error =
        "KeyMint hardware initialization failed: " + status.getDescription();
    return false;
  }

  namespace ss = aidl::android::hardware::security::sharedsecret;
  ndk::SpAIBinder binder(AServiceManager_checkService(kSharedSecretService));
  auto shared_secret = ss::ISharedSecret::fromBinder(binder);
  if (!shared_secret) {
    *error = "KeyMint shared-secret HAL is unavailable";
    return false;
  }
  ss::SharedSecretParameters parameters;
  status = shared_secret->getSharedSecretParameters(&parameters);
  if (!status.isOk()) {
    *error =
        "KeyMint shared-secret parameters failed: " + status.getDescription();
    return false;
  }
  std::vector<uint8_t> sharing_check;
  status = shared_secret->computeSharedSecret({parameters}, &sharing_check);
  if (!status.isOk() || sharing_check.size() != 32) {
    *error =
        "KeyMint shared-secret negotiation failed: " + status.getDescription();
    Zeroize(&sharing_check);
    return false;
  }
  Zeroize(&sharing_check);
  initialized = true;
  return true;
}

bool VerifyGatekeeperHandle(
    int32_t gatekeeper_user_id, const std::vector<uint8_t> &password_handle,
    const std::vector<uint8_t> &gatekeeper_password,
    std::optional<km::HardwareAuthToken> *auth_token,
    bool *credential_rejected, std::string *error) {
  *credential_rejected = false;
  ndk::SpAIBinder gatekeeper_binder(
      AServiceManager_checkService(kGatekeeperService));
  auto gatekeeper =
      aidl::android::hardware::gatekeeper::IGatekeeper::fromBinder(
          gatekeeper_binder);
  if (!gatekeeper) {
    *error = "Gatekeeper HAL is unavailable";
    return false;
  }
  aidl::android::hardware::gatekeeper::GatekeeperVerifyResponse response;
  auto status = gatekeeper->verify(gatekeeper_user_id, 0, password_handle,
                                   gatekeeper_password, &response);
  if (!status.isOk()) {
    // QTI maps the secure application's KM_ERROR_VERIFICATION_FAILED result to
    // Gatekeeper's generic service-specific error instead of returning it in
    // GatekeeperVerifyResponse.  Treat that as a bad credential, not as a
    // Binder/HAL transport failure.
    if (status.getServiceSpecificError() ==
        aidl::android::hardware::gatekeeper::IGatekeeper::
            ERROR_GENERAL_FAILURE) {
      *credential_rejected = true;
      *error = "credential verification failed";
      return false;
    }
    *error = "Gatekeeper transaction failed: " + status.getDescription();
    return false;
  }
  if (response.statusCode ==
      aidl::android::hardware::gatekeeper::IGatekeeper::ERROR_RETRY_TIMEOUT) {
    *credential_rejected = true;
    *error = "credential throttled; retry after " +
             std::to_string(response.timeoutMs) + " ms";
    return false;
  }
  if (response.statusCode <
      aidl::android::hardware::gatekeeper::IGatekeeper::STATUS_OK) {
    *credential_rejected = true;
    *error = "credential verification failed";
    return false;
  }
  *auth_token = std::move(response.hardwareAuthToken);
  return true;
}

bool VerifyGatekeeper(uint32_t user_id, const PasswordData &pwd,
                      const std::vector<uint8_t> &stretched,
                      std::optional<km::HardwareAuthToken> *auth_token,
                      bool *credential_rejected, std::string *error) {
  std::vector<uint8_t> gatekeeper_password =
      PersonalizedHash("user-gk-authentication", stretched);
  const bool verified = VerifyGatekeeperHandle(
      kFakeUserIdBase + static_cast<int32_t>(user_id), pwd.password_handle,
      gatekeeper_password, auth_token, credential_rejected, error);
  Zeroize(&gatekeeper_password);
  return verified;
}

bool IsKeyMintError(const ndk::ScopedAStatus &status, km::ErrorCode error) {
  return !status.isOk() &&
         status.getServiceSpecificError() == static_cast<int32_t>(error);
}

std::vector<uint8_t>
KeyMintDecrypt(const std::vector<uint8_t> &key_blob,
               const std::vector<uint8_t> &ciphertext,
               const std::vector<uint8_t> &application_id,
               const std::optional<km::HardwareAuthToken> &auth_token,
               std::string *error) {
  constexpr size_t kNonceSize = 12;
  constexpr size_t kTagSize = 16;
  if (ciphertext.size() < kNonceSize + kTagSize) {
    *error = "invalid KeyMint AES-GCM ciphertext";
    return {};
  }

  auto keymint = GetKeyMint(error);
  if (!keymint)
    return {};

  std::vector<uint8_t> nonce(ciphertext.begin(),
                             ciphertext.begin() + kNonceSize);
  // Match vold's beginParams() + decryptWithKeystoreKey() ordering exactly.
  // Keystore2 consumes TAG_PURPOSE itself and intentionally does not forward
  // that tag to IKeyMintDevice::begin().
  auto parameter_builder =
      km::AuthorizationSetBuilder().GcmModeMacLen(kTagSize * 8);
  if (!application_id.empty())
    parameter_builder.Authorization(km::TAG_APPLICATION_ID, application_id);
  parameter_builder.Authorization(km::TAG_NONCE, nonce);
  const auto parameters = parameter_builder.vector_data();

  std::vector<uint8_t> usable_blob = key_blob;
  km::BeginResult begin_result;
  auto status = keymint->begin(km::KeyPurpose::DECRYPT, usable_blob, parameters,
                               auth_token, &begin_result);
  if (IsKeyMintError(status, km::ErrorCode::KEY_REQUIRES_UPGRADE)) {
    std::vector<uint8_t> upgraded_blob;
    status = keymint->upgradeKey(usable_blob, parameters, &upgraded_blob);
    if (!status.isOk()) {
      *error = "KeyMint key upgrade failed: " + status.getDescription();
      Zeroize(&usable_blob);
      return {};
    }
    Zeroize(&usable_blob);
    usable_blob = std::move(upgraded_blob);
    status = keymint->begin(km::KeyPurpose::DECRYPT, usable_blob, parameters,
                            auth_token, &begin_result);
  }
  Zeroize(&usable_blob);
  if (!status.isOk() || !begin_result.operation) {
    *error = "KeyMint decrypt begin failed: " + status.getDescription();
    return {};
  }

  std::vector<uint8_t> body(ciphertext.begin() + kNonceSize, ciphertext.end());
  std::vector<uint8_t> plaintext;
  status = begin_result.operation->update(body, auth_token, std::nullopt,
                                          &plaintext);
  Zeroize(&body);
  if (!status.isOk()) {
    *error = "KeyMint decrypt update failed: " + status.getDescription();
    Zeroize(&plaintext);
    return {};
  }

  std::vector<uint8_t> final_output;
  status =
      begin_result.operation->finish(std::nullopt, std::nullopt, auth_token,
                                     std::nullopt, std::nullopt, &final_output);
  if (!status.isOk()) {
    *error = "KeyMint decrypt finish failed: " + status.getDescription();
    Zeroize(&plaintext);
    Zeroize(&final_output);
    return {};
  }
  plaintext.insert(plaintext.end(), final_output.begin(), final_output.end());
  Zeroize(&final_output);
  return plaintext;
}

struct StoredKeyBlob {
  std::vector<uint8_t> blob;
  bool encrypted_by_password = false;
  std::optional<int64_t> encrypted_by_key_id;
};

void ReadSqlBlob(sqlite3_stmt *statement, int column,
                 std::vector<uint8_t> *result) {
  const auto *blob =
      static_cast<const uint8_t *>(sqlite3_column_blob(statement, column));
  const int size = sqlite3_column_bytes(statement, column);
  if (blob && size > 0)
    result->assign(blob, blob + size);
}

std::optional<StoredKeyBlob>
LoadStoredKey(int key_type, int domain, int64_t key_namespace,
              const std::string &alias, std::string *error) {
  sqlite3 *db = nullptr;
  if (sqlite3_open_v2(kKeystoreDb, &db,
                      SQLITE_OPEN_READONLY | SQLITE_OPEN_FULLMUTEX,
                      nullptr) != SQLITE_OK) {
    if (db)
      sqlite3_close(db);
    *error = "cannot open Keystore2 database";
    return std::nullopt;
  }

  constexpr char query[] =
      "SELECT b.id,b.blob FROM keyentry k JOIN blobentry b ON "
      "b.keyentryid=k.id WHERE k.key_type=? AND k.domain=? AND "
      "k.namespace=? AND k.alias=? AND k.state=1 AND "
      "b.subcomponent_type=0 AND b.state=0 ORDER BY b.id DESC LIMIT 1";
  sqlite3_stmt *statement = nullptr;
  StoredKeyBlob result;
  int64_t blob_id = 0;
  bool found = false;
  if (sqlite3_prepare_v2(db, query, -1, &statement, nullptr) == SQLITE_OK &&
      sqlite3_bind_int(statement, 1, key_type) == SQLITE_OK &&
      sqlite3_bind_int(statement, 2, domain) == SQLITE_OK &&
      sqlite3_bind_int64(statement, 3, key_namespace) == SQLITE_OK &&
      sqlite3_bind_text(statement, 4, alias.c_str(), -1, SQLITE_TRANSIENT) ==
          SQLITE_OK &&
      sqlite3_step(statement) == SQLITE_ROW) {
    blob_id = sqlite3_column_int64(statement, 0);
    ReadSqlBlob(statement, 1, &result.blob);
    found = true;
  }
  if (statement)
    sqlite3_finalize(statement);
  statement = nullptr;

  constexpr char metadata_query[] =
      "SELECT tag,data FROM blobmetadata WHERE blobentryid=? ORDER BY tag";
  if (found &&
      sqlite3_prepare_v2(db, metadata_query, -1, &statement, nullptr) ==
          SQLITE_OK &&
      sqlite3_bind_int64(statement, 1, blob_id) == SQLITE_OK) {
    while (sqlite3_step(statement) == SQLITE_ROW) {
      switch (sqlite3_column_int(statement, 0)) {
      case 0:
        if (sqlite3_column_type(statement, 1) == SQLITE_NULL)
          result.encrypted_by_password = true;
        else
          result.encrypted_by_key_id = sqlite3_column_int64(statement, 1);
        break;
      default:
        break;
      }
    }
  }
  if (statement)
    sqlite3_finalize(statement);
  sqlite3_close(db);
  if (result.blob.empty()) {
    *error = "key " + alias + " is missing from Keystore2 database";
    return std::nullopt;
  }
  return result;
}

std::vector<uint8_t> LoadProtectorKey(uint64_t protector_id,
                                      std::string *error) {
  std::ostringstream alias;
  alias << "synthetic_password_" << std::hex << protector_id;
  auto stored = LoadStoredKey(0, 2, 103, alias.str(), error);
  if (!stored)
    return {};
  if (stored->encrypted_by_password || stored->encrypted_by_key_id) {
    *error = "Synthetic Password protector key is unexpectedly encrypted by "
             "a Keystore2 super key";
    Zeroize(&stored->blob);
    return {};
  }
  return std::move(stored->blob);
}

std::vector<uint8_t>
DecryptProtectorLayer(uint64_t protector_id,
                      const std::vector<uint8_t> &ciphertext,
                      const std::optional<km::HardwareAuthToken> &auth_token,
                      std::string *error) {
  std::vector<uint8_t> key_blob = LoadProtectorKey(protector_id, error);
  if (key_blob.empty())
    return {};
  std::vector<uint8_t> result =
      KeyMintDecrypt(key_blob, ciphertext, {}, auth_token, error);
  Zeroize(&key_blob);
  return result;
}

std::vector<uint8_t> DeriveSpSubkey(uint8_t version,
                                    const std::vector<uint8_t> &sp,
                                    std::string_view label) {
  if (version != 3)
    return PersonalizedHash(label, sp);
  static constexpr std::string_view context =
      "android-synthetic-password-personalization-context";
  std::vector<uint8_t> input;
  auto append_be32 = [&](uint32_t value) {
    input.push_back(value >> 24);
    input.push_back(value >> 16);
    input.push_back(value >> 8);
    input.push_back(value);
  };
  append_be32(1);
  input.insert(input.end(), label.begin(), label.end());
  input.push_back(0);
  input.insert(input.end(), context.begin(), context.end());
  append_be32(static_cast<uint32_t>(context.size() * 8));
  append_be32(256);
  std::vector<uint8_t> output(SHA256_DIGEST_LENGTH);
  unsigned output_size = 0;
  HMAC(EVP_sha256(), sp.data(), sp.size(), input.data(), input.size(),
       output.data(), &output_size);
  Zeroize(&input);
  if (output_size != output.size())
    Zeroize(&output);
  return output;
}

std::vector<uint8_t> DeriveFbeKey(uint8_t version,
                                  const std::vector<uint8_t> &sp) {
  return DeriveSpSubkey(version, sp, "fbe-key");
}

std::vector<uint8_t>
LoadProfileDecryptKey(uint32_t profile_user_id, std::string *error) {
  // LockSettingsService obtains its KeyStore from SyntheticPasswordCrypto,
  // which loads AndroidKeyStore with NAMESPACE_LOCKSETTINGS.  Consequently
  // current profile keys live beside SP protector keys in SELinux namespace
  // 103 and are not wrapped by a per-user Keystore2 super key.
  auto stored = LoadStoredKey(0, 2, 103,
                              "profile_key_name_decrypt_" +
                                  std::to_string(profile_user_id),
                              error);
  if (!stored)
    return {};
  if (stored->encrypted_by_password || stored->encrypted_by_key_id) {
    *error = "profile decrypt key is unexpectedly wrapped by a Keystore2 "
             "super key";
    Zeroize(&stored->blob);
    return {};
  }
  return std::move(stored->blob);
}

bool GetSyntheticPasswordAuthToken(
    uint32_t user_id, uint8_t sp_version, const std::vector<uint8_t> &sp,
    std::optional<km::HardwareAuthToken> *auth_token, std::string *error) {
  std::vector<uint8_t> handle;
  if (!ReadFile(UserSpDir(user_id) + "/0000000000000000.handle", &handle)) {
    *error = "Synthetic Password Gatekeeper handle is missing for user " +
             std::to_string(user_id);
    return false;
  }
  std::vector<uint8_t> password =
      DeriveSpSubkey(sp_version, sp, "sp-gk-authentication");
  bool rejected = false;
  const bool verified = VerifyGatekeeperHandle(
      static_cast<int32_t>(user_id), handle, password, auth_token, &rejected,
      error);
  Zeroize(&password);
  Zeroize(&handle);
  return verified;
}

bool ReadStorageKey(const std::string &directory,
                    const std::vector<uint8_t> &secret,
                    std::vector<uint8_t> *ephemeral_key, std::string *error) {
  std::vector<uint8_t> version;
  std::vector<uint8_t> secdiscardable;
  std::vector<uint8_t> encrypted_key;
  if (!ReadFile(directory + "/version", &version) || version.size() != 1 ||
      version[0] != '1' ||
      !ReadFile(directory + "/encrypted_key", &encrypted_key)) {
    *error = "incomplete or unsupported storage key directory " + directory;
    return false;
  }

  std::vector<uint8_t> app_id;
  if (ReadFile(directory + "/secdiscardable", &secdiscardable)) {
    app_id = PersonalizedHash("Android secdiscardable SHA512", secdiscardable);
  }
  app_id.insert(app_id.end(), secret.begin(), secret.end());
  Zeroize(&secdiscardable);

  std::vector<uint8_t> long_lived_key;
  if (secret.empty()) {
    std::vector<uint8_t> wrapping_key;
    if (!ReadFile(directory + "/keymaster_key_blob", &wrapping_key) &&
        !ReadFile(directory + "/keymaster_key_blob_upgraded", &wrapping_key)) {
      *error = "KeyMint wrapping key is missing from " + directory;
      Zeroize(&app_id);
      Zeroize(&encrypted_key);
      return false;
    }
    long_lived_key =
        KeyMintDecrypt(wrapping_key, encrypted_key, app_id, {}, error);
    Zeroize(&wrapping_key);
  } else {
    std::vector<uint8_t> software_key =
        PersonalizedHash("Android key wrapping key generation SHA512", app_id);
    software_key.resize(32);
    long_lived_key = AesGcmDecrypt(software_key, encrypted_key);
    Zeroize(&software_key);
    if (long_lived_key.empty())
      *error = "software storage-key unwrap failed for " + directory;
  }
  Zeroize(&app_id);
  Zeroize(&encrypted_key);
  if (long_lived_key.empty())
    return false;

  auto keymint = GetKeyMint(error);
  if (!keymint) {
    Zeroize(&long_lived_key);
    return false;
  }
  auto status =
      keymint->convertStorageKeyToEphemeral(long_lived_key, ephemeral_key);
  if (IsKeyMintError(status, km::ErrorCode::KEY_REQUIRES_UPGRADE)) {
    std::vector<uint8_t> upgraded_key;
    status = keymint->upgradeKey(long_lived_key, {}, &upgraded_key);
    if (status.isOk()) {
      Zeroize(&long_lived_key);
      long_lived_key = std::move(upgraded_key);
      status =
          keymint->convertStorageKeyToEphemeral(long_lived_key, ephemeral_key);
    }
  }
  Zeroize(&long_lived_key);
  if (!status.isOk()) {
    *error = "storage-key conversion failed for " + directory + ": " +
             status.getDescription();
    Zeroize(ephemeral_key);
    return false;
  }
  return true;
}

std::string Hex(const std::vector<uint8_t> &value) {
  constexpr char digits[] = "0123456789abcdef";
  std::string result(value.size() * 2, '\0');
  for (size_t i = 0; i < value.size(); ++i) {
    result[i * 2] = digits[value[i] >> 4];
    result[i * 2 + 1] = digits[value[i] & 0xf];
  }
  return result;
}

std::optional<std::vector<uint8_t>> Unhex(std::string_view value) {
  if (value.size() % 2 != 0)
    return std::nullopt;
  auto nibble = [](char c) -> int {
    if (c >= '0' && c <= '9')
      return c - '0';
    if (c >= 'a' && c <= 'f')
      return c - 'a' + 10;
    if (c >= 'A' && c <= 'F')
      return c - 'A' + 10;
    return -1;
  };
  std::vector<uint8_t> result(value.size() / 2);
  for (size_t i = 0; i < result.size(); ++i) {
    const int high = nibble(value[i * 2]);
    const int low = nibble(value[i * 2 + 1]);
    if (high < 0 || low < 0) {
      Zeroize(&result);
      return std::nullopt;
    }
    result[i] = static_cast<uint8_t>((high << 4) | low);
  }
  return result;
}

std::string Base64(const std::vector<uint8_t> &value) {
  size_t encoded_size = 0;
  if (EVP_EncodedLength(&encoded_size, value.size()) != 1)
    return {};
  // EVP_EncodedLength includes the trailing NUL written by EVP_EncodeBlock.
  std::string result(encoded_size, '\0');
  const size_t size = EVP_EncodeBlock(
      reinterpret_cast<uint8_t *>(result.data()), value.data(), value.size());
  result.resize(size);
  return result;
}

std::unique_ptr<EVP_PKEY, decltype(&EVP_PKEY_free)>
MakePublicKey(const Certificate &certificate) {
  std::unique_ptr<EVP_PKEY, decltype(&EVP_PKEY_free)> key(EVP_PKEY_new(),
                                                          EVP_PKEY_free);
  if (!key || certificate.key_type != Certificate::KEY_TYPE_RSA ||
      !certificate.rsa ||
      EVP_PKEY_set1_RSA(key.get(), certificate.rsa.get()) != 1) {
    key.reset();
  }
  return key;
}

std::string PublicKeyFingerprint(EVP_PKEY *key) {
  const int encoded_size = i2d_PUBKEY(key, nullptr);
  if (encoded_size <= 0)
    return {};
  std::vector<uint8_t> encoded(static_cast<size_t>(encoded_size));
  uint8_t *at = encoded.data();
  if (i2d_PUBKEY(key, &at) != encoded_size) {
    Zeroize(&encoded);
    return {};
  }
  std::vector<uint8_t> digest(SHA256_DIGEST_LENGTH);
  SHA256(encoded.data(), encoded.size(), digest.data());
  Zeroize(&encoded);
  const std::string result = Hex(digest);
  Zeroize(&digest);
  return result;
}

std::vector<uint8_t>
EncryptMaintenanceSecret(EVP_PKEY *key, const std::vector<uint8_t> &secret) {
  std::unique_ptr<EVP_PKEY_CTX, decltype(&EVP_PKEY_CTX_free)> context(
      EVP_PKEY_CTX_new(key, nullptr), EVP_PKEY_CTX_free);
  if (!context || EVP_PKEY_encrypt_init(context.get()) <= 0 ||
      EVP_PKEY_CTX_set_rsa_padding(context.get(), RSA_PKCS1_OAEP_PADDING) <=
          0 ||
      EVP_PKEY_CTX_set_rsa_oaep_md(context.get(), EVP_sha256()) <= 0 ||
      EVP_PKEY_CTX_set_rsa_mgf1_md(context.get(), EVP_sha256()) <= 0) {
    return {};
  }
  size_t size = 0;
  if (EVP_PKEY_encrypt(context.get(), nullptr, &size, secret.data(),
                       secret.size()) <= 0) {
    return {};
  }
  std::vector<uint8_t> result(size);
  if (EVP_PKEY_encrypt(context.get(), result.data(), &size, secret.data(),
                       secret.size()) <= 0) {
    Zeroize(&result);
    return {};
  }
  result.resize(size);
  return result;
}

bool IsMounted(const char *mountpoint);

bool g_auth_state_loaded = false;
uint32_t g_failed_auth_attempts = 0;
std::chrono::steady_clock::time_point g_auth_retry_after{};
std::vector<uint8_t> g_maintenance_secret;
std::string g_maintenance_challenge;
std::chrono::steady_clock::time_point g_maintenance_challenge_expires{};

std::chrono::seconds AuthDelayForFailure(uint32_t failures) {
  if (failures == 0)
    return 0s;
  const uint32_t shift = std::min(failures - 1, uint32_t{8});
  return std::chrono::seconds(std::min(uint32_t{1} << shift, uint32_t{300}));
}

void PersistAuthFailures(int64_t retry_after_unix) {
  if (!IsMounted("/metadata"))
    return;
  if (mkdir("/metadata/ota", 0750) != 0 && errno != EEXIST) {
    PLOG(WARNING) << "cannot create recovery authentication state directory";
    return;
  }
  const std::string temporary = std::string(kAuthFailureState) + ".tmp";
  const std::string state = std::to_string(g_failed_auth_attempts) + " " +
                            std::to_string(retry_after_unix) + "\n";
  if (!android::base::WriteStringToFile(state, temporary, 0600, 0, 0) ||
      rename(temporary.c_str(), kAuthFailureState) != 0) {
    PLOG(WARNING) << "cannot persist recovery authentication delay";
  }
}

void LoadAuthFailures() {
  if (g_auth_state_loaded || !IsMounted("/metadata"))
    return;
  g_auth_state_loaded = true;
  std::string state;
  uint32_t failures = 0;
  int64_t retry_after_unix = 0;
  bool damaged_state = false;
  if (android::base::ReadFileToString(kAuthFailureState, &state)) {
    std::istringstream input(state);
    if (!(input >> failures >> retry_after_unix)) {
      damaged_state = true;
    } else {
      input >> std::ws;
      damaged_state = !input.eof();
    }
  }
  if (damaged_state) {
    // Fail closed for a damaged state file, while keeping the recovery usable
    // again after a bounded delay.
    failures = 9;
    retry_after_unix = 0;
  }
  g_failed_auth_attempts = std::min(failures, uint32_t{31});
  const int64_t now = static_cast<int64_t>(time(nullptr));
  std::chrono::seconds delay{0};
  if (damaged_state) {
    delay = AuthDelayForFailure(g_failed_auth_attempts);
  } else if (retry_after_unix > now) {
    // A backwards RTC adjustment must not turn a persisted deadline into a
    // bypass. Clamp it to the same maximum used by the exponential backoff.
    delay =
        std::chrono::seconds(std::min<int64_t>(retry_after_unix - now, 300));
  } else if (g_failed_auth_attempts != 0 && now < 1609459200) {
    // An invalid RTC must not make a power cycle bypass an active failure
    // counter.
    delay = AuthDelayForFailure(g_failed_auth_attempts);
  }
  g_auth_retry_after = std::chrono::steady_clock::now() + delay;
}

std::chrono::seconds RemainingAuthDelay() {
  const auto remaining = g_auth_retry_after - std::chrono::steady_clock::now();
  if (remaining <= 0s)
    return 0s;
  return std::chrono::duration_cast<std::chrono::seconds>(remaining + 999ms);
}

std::chrono::seconds RecordFailedAuth() {
  g_failed_auth_attempts = std::min(g_failed_auth_attempts + 1, uint32_t{31});
  const auto delay = AuthDelayForFailure(g_failed_auth_attempts);
  g_auth_retry_after = std::chrono::steady_clock::now() + delay;
  PersistAuthFailures(static_cast<int64_t>(time(nullptr)) + delay.count());
  return delay;
}

void ClearAuthFailures() {
  g_failed_auth_attempts = 0;
  g_auth_retry_after = {};
  PersistAuthFailures(0);
}

void SetAdbAuthorization(const char *method) {
  if (!android::base::SetProperty("sys.recovery.adb.authorized", method))
    LOG(WARNING) << "cannot publish recovery ADB authorization state";
}

std::string NewMaintenanceChallenge() {
  const auto delay = RemainingAuthDelay();
  if (delay > 0s) {
    return "ERROR: authentication delayed; retry after " +
           std::to_string(delay.count()) + " seconds";
  }
  const auto now = std::chrono::steady_clock::now();
  if (!g_maintenance_secret.empty() && now < g_maintenance_challenge_expires) {
    return g_maintenance_challenge;
  }

  Zeroize(&g_maintenance_secret);
  g_maintenance_challenge.clear();
  g_maintenance_secret.resize(kMaintenanceSecretBytes);
  if (RAND_bytes(g_maintenance_secret.data(), g_maintenance_secret.size()) !=
      1) {
    Zeroize(&g_maintenance_secret);
    return "ERROR: secure random generator failed";
  }

  const auto certificates = LoadKeysFromZipfile(kOtaCertificates);
  std::ostringstream challenge;
  challenge << "MAINTENANCE-CHALLENGE-V1\n"
            << "padding=RSA-OAEP-SHA256\n";
  size_t usable_keys = 0;
  for (const auto &certificate : certificates) {
    auto key = MakePublicKey(certificate);
    if (!key)
      continue;
    auto ciphertext = EncryptMaintenanceSecret(key.get(), g_maintenance_secret);
    const std::string fingerprint = PublicKeyFingerprint(key.get());
    const std::string encoded = Base64(ciphertext);
    Zeroize(&ciphertext);
    if (fingerprint.empty() || encoded.empty())
      continue;
    challenge << "key_sha256=" << fingerprint << '\n'
              << "ciphertext_base64=" << encoded << '\n';
    ++usable_keys;
  }
  if (usable_keys == 0) {
    Zeroize(&g_maintenance_secret);
    return "ERROR: no RSA OTA signing certificate is available";
  }
  challenge << "Decrypt one ciphertext with the matching ROM signing private "
               "key and enter the 64-character hex plaintext.\n";
  g_maintenance_challenge = challenge.str();
  g_maintenance_challenge_expires = now + kMaintenanceChallengeLifetime;
  return g_maintenance_challenge;
}

std::string VerifyMaintenanceResponse(const std::vector<uint8_t> &response) {
  const auto delay = RemainingAuthDelay();
  if (delay > 0s) {
    return "ERROR: authentication delayed; retry after " +
           std::to_string(delay.count()) + " seconds";
  }
  if (g_maintenance_secret.empty() ||
      std::chrono::steady_clock::now() >= g_maintenance_challenge_expires) {
    Zeroize(&g_maintenance_secret);
    g_maintenance_challenge.clear();
    return "ERROR: maintenance challenge is missing or expired";
  }
  const std::string_view text(reinterpret_cast<const char *>(response.data()),
                              response.size());
  auto decoded = Unhex(text);
  const bool matches =
      decoded && decoded->size() == g_maintenance_secret.size() &&
      CRYPTO_memcmp(decoded->data(), g_maintenance_secret.data(),
                    g_maintenance_secret.size()) == 0;
  if (decoded)
    Zeroize(&*decoded);
  if (!matches) {
    const auto retry = RecordFailedAuth();
    return "ERROR: invalid maintenance response; retry after " +
           std::to_string(retry.count()) + " seconds";
  }
  Zeroize(&g_maintenance_secret);
  g_maintenance_challenge.clear();
  ClearAuthFailures();
  SetAdbAuthorization("maintenance");
  return "OK: recovery ADB authorized by ROM signing key";
}

bool IsMounted(const char *mountpoint) {
  std::string mounts;
  if (!android::base::ReadFileToString("/proc/self/mounts", &mounts))
    return false;
  const std::string needle = " " + std::string(mountpoint) + " ";
  return mounts.find(needle) != std::string::npos;
}

bool MountMetadata(std::string *error) {
  if (IsMounted("/metadata"))
    return true;
  if (mount(kMetadataBlock, "/metadata", "f2fs",
            MS_NOATIME | MS_NOSUID | MS_NODEV, "discard") == 0)
    return true;
  if (errno == EBUSY && IsMounted("/metadata"))
    return true;
  *error = "cannot mount metadata: " + std::string(strerror(errno));
  return false;
}

bool MountMetadataEncryptedData(std::string *error) {
  if (!MountMetadata(error))
    return false;
  LoadAuthFailures();
  // Keystore2 performs these two initialization steps before vold asks it to
  // unwrap the metadata key.  Recovery talks to KeyMint directly, so it must
  // perform the same startup handshake itself.
  if (!InitializeKeyMint(error))
    return false;
  // Recovery or an earlier daemon instance may already have activated and
  // mounted metadata-encrypted userdata.  KeyMint still needs initialization
  // because the caller will immediately unwrap the DE keys below.
  if (IsMounted("/data"))
    return true;

  std::vector<uint8_t> key;
  if (!ReadStorageKey(kMetadataKeyDir, {}, &key, error))
    return false;

  unique_fd block(open(kUserdataBlock, O_RDONLY | O_CLOEXEC));
  uint64_t bytes = 0;
  if (block.get() < 0 || ioctl(block.get(), BLKGETSIZE64, &bytes) != 0) {
    *error = "cannot determine userdata block-device size";
    Zeroize(&key);
    return false;
  }
  uint64_t sectors = (bytes / 512) & ~uint64_t{7};
  auto target = std::make_unique<DmTargetDefaultKey>(
      0, sectors, "aes-xts-plain64", Hex(key), kUserdataBlock, 0);
  Zeroize(&key);
  target->SetSetDun();
  target->SetWrappedKeyV0();
  DmTable table;
  if (!table.AddTarget(std::move(target))) {
    *error = "invalid userdata dm-default-key table";
    return false;
  }

  auto &dm = DeviceMapper::Instance();
  std::string mapper;
  const DmDeviceState state = dm.GetState("userdata");
  if (state == DmDeviceState::SUSPENDED) {
    if (!dm.LoadTableAndActivate("userdata", table) ||
        !dm.WaitForDevice("userdata", 20s, &mapper)) {
      *error = "cannot activate pre-created userdata dm device";
      return false;
    }
  } else if (state == DmDeviceState::ACTIVE) {
    if (!dm.GetDmDevicePathByName("userdata", &mapper)) {
      *error = "cannot resolve active userdata dm device";
      return false;
    }
  } else if (!dm.CreateDevice("userdata", table, &mapper, 5s)) {
    *error = "cannot create userdata dm-default-key device";
    return false;
  }

  // Keep this exactly aligned with haotian's fstab.qcom fs_mgr mount options.
  constexpr char options[] =
      "discard,reserve_root=32768,resgid=1065,fsync_mode=nobarrier,inlinecrypt,"
      "gc_merge,compress_mode=user,compress_cache,atgc,age_extent_cache";
  if (mount("/dev/block/mapper/userdata", "/data", "f2fs",
            MS_NOATIME | MS_NOSUID | MS_NODEV, options) != 0 &&
      errno != EBUSY) {
    *error = "cannot mount metadata-decrypted userdata";
    return false;
  }
  return true;
}

bool InstallFscryptKey(const std::vector<uint8_t> &key, std::string *error) {
  const size_t allocation = sizeof(fscrypt_add_key_arg) + key.size();
  std::vector<uint8_t> storage(allocation, 0);
  auto *argument = reinterpret_cast<fscrypt_add_key_arg *>(storage.data());
  argument->key_spec.type = FSCRYPT_KEY_SPEC_TYPE_IDENTIFIER;
  argument->__flags |= __FSCRYPT_ADD_KEY_FLAG_HW_WRAPPED;
  argument->raw_size = key.size();
  memcpy(argument->raw, key.data(), key.size());
  unique_fd data(open("/data", O_RDONLY | O_DIRECTORY | O_CLOEXEC));
  if (data.get() < 0 ||
      ioctl(data.get(), FS_IOC_ADD_ENCRYPTION_KEY, argument) != 0) {
    *error = "cannot install hardware-wrapped fscrypt key";
    OPENSSL_cleanse(storage.data(), storage.size());
    return false;
  }
  OPENSSL_cleanse(storage.data(), storage.size());
  return true;
}

bool RetrieveAndInstallKey(const std::string &directory,
                           const std::vector<uint8_t> &secret,
                           std::string *error) {
  std::vector<uint8_t> key;
  if (!ReadStorageKey(directory, secret, &key, error))
    return false;
  const bool installed = InstallFscryptKey(key, error);
  Zeroize(&key);
  return installed;
}

bool g_system_de_ready = false;
bool g_de_ready = false;
bool g_ce_ready = false;
std::set<uint32_t> g_de_users;
std::set<uint32_t> g_ce_users;

std::string Unlock(const RequestHeader &request,
                   std::vector<uint8_t> credential, bool authorize_adb,
                   bool managed_credential = false);

std::string MountData() {
  std::string error;
  if (!MountMetadataEncryptedData(&error))
    return "ERROR: " + error;
  if (!g_system_de_ready) {
    if (!RetrieveAndInstallKey(kSystemDeKeyDir, {}, &error))
      return "ERROR: systemwide DE key: " + error;
    g_system_de_ready = true;
  }
  for (uint32_t user_id : ListNumericDirectories(kUserDeKeyRoot)) {
    if (g_de_users.contains(user_id))
      continue;
    if (!RetrieveAndInstallKey(UserDeKeyDir(user_id), {}, &error)) {
      if (user_id == 0)
        return "ERROR: user 0 DE key: " + error;
      LOG(WARNING) << "cannot install DE key for user " << user_id << ": "
                   << error;
      continue;
    }
    g_de_users.insert(user_id);
  }
  g_de_ready = g_de_users.contains(0);
  if (!g_de_ready)
    return "ERROR: user 0 DE key directory is missing";
  return "DE mounted for " + std::to_string(g_de_users.size()) + " user(s)";
}

bool HasLockedUnifiedProfiles() {
  for (uint32_t user_id : ListUsers()) {
    if (!g_ce_users.contains(user_id) &&
        access(UnifiedProfileKeyFile(user_id).c_str(), F_OK) == 0) {
      return true;
    }
  }
  return false;
}

std::string UnlockUnifiedProfiles(
    uint32_t parent_user_id,
    const std::optional<km::HardwareAuthToken> &parent_auth_token) {
  if (parent_user_id != 0 || !parent_auth_token ||
      !HasLockedUnifiedProfiles()) {
    return {};
  }
  std::string error;
  std::vector<uint32_t> unlocked;
  std::vector<std::string> failures;
  for (uint32_t profile_user_id : ListUsers()) {
    if (profile_user_id == parent_user_id ||
        g_ce_users.contains(profile_user_id)) {
      continue;
    }
    std::vector<uint8_t> encrypted_credential;
    if (!ReadFile(UnifiedProfileKeyFile(profile_user_id),
                  &encrypted_credential)) {
      continue;
    }
    ScopedZeroize encrypted_cleanup(&encrypted_credential);
    std::vector<uint8_t> profile_key =
        LoadProfileDecryptKey(profile_user_id, &error);
    if (profile_key.empty()) {
      failures.push_back("user " + std::to_string(profile_user_id) + ": " +
                         error);
      continue;
    }
    std::vector<uint8_t> profile_credential =
        KeyMintDecrypt(profile_key, encrypted_credential, {},
                       parent_auth_token, &error);
    Zeroize(&profile_key);
    if (profile_credential.empty()) {
      failures.push_back("user " + std::to_string(profile_user_id) + ": " +
                         error);
      continue;
    }
    RequestHeader request{
        .magic = kRequestMagic,
        .kind = static_cast<uint32_t>(RequestKind::kPassword),
        .user_id = profile_user_id,
    };
    const std::string result =
        Unlock(request, std::move(profile_credential), false, true);
    if (android::base::StartsWith(result, "OK:"))
      unlocked.push_back(profile_user_id);
    else
      failures.push_back("user " + std::to_string(profile_user_id) + ": " +
                         result);
  }

  std::ostringstream result;
  bool has_result = false;
  if (!unlocked.empty()) {
    result << "unified profiles unlocked=";
    for (size_t i = 0; i < unlocked.size(); ++i) {
      if (i != 0)
        result << ',';
      result << unlocked[i];
    }
    has_result = true;
  }
  if (!failures.empty()) {
    if (has_result)
      result << "; ";
    result << "WARNING: unified profile unlock failed (";
    for (size_t i = 0; i < failures.size(); ++i) {
      if (i != 0)
        result << "; ";
      result << failures[i];
    }
    result << ')';
  }
  return result.str();
}

std::string Unlock(const RequestHeader &request,
                   std::vector<uint8_t> credential, bool authorize_adb,
                   bool managed_credential) {
  ScopedZeroize credential_cleanup(&credential);
  if (!IsValidUserId(request.user_id))
    return "ERROR: invalid Android user ID";
  const uint32_t user_id = request.user_id;
  if (g_ce_users.contains(user_id) &&
      (user_id != 0 || !HasLockedUnifiedProfiles())) {
    if (authorize_adb)
      SetAdbAuthorization("credential");
    return "OK: user " + std::to_string(user_id) +
           " CE storage is already unlocked";
  }
  if (!g_de_users.contains(user_id)) {
    const std::string mount_result = MountData();
    if (android::base::StartsWith(mount_result, "ERROR:"))
      return mount_result;
  }
  if (!g_de_users.contains(user_id))
    return "ERROR: DE storage is unavailable for user " +
           std::to_string(user_id);
  std::string error;
  auto protector = LoadProtector(user_id, &error);
  if (!protector)
    return "ERROR: " + error;
  if (!managed_credential && user_id != 0 &&
      access(UnifiedProfileKeyFile(user_id).c_str(), F_OK) == 0) {
    return "ERROR: user " + std::to_string(user_id) +
           " uses a unified profile credential; unlock user 0 with the "
           "parent credential instead";
  }

  int expected_type = kCredentialNone;
  switch (static_cast<RequestKind>(request.kind)) {
  case RequestKind::kNone:
    expected_type = kCredentialNone;
    break;
  case RequestKind::kPattern:
    expected_type = kCredentialPattern;
    break;
  case RequestKind::kPin:
    expected_type = kCredentialPin;
    break;
  case RequestKind::kPassword:
    expected_type = kCredentialPassword;
    break;
  default:
    return "ERROR: invalid unlock request";
  }
  const int stored_type = protector->password_data
                              ? protector->password_data->credential_type
                              : kCredentialNone;
  if (stored_type != expected_type) {
    return "ERROR: requested credential type does not match stored type " +
           std::to_string(stored_type);
  }
  if (expected_type == kCredentialPattern) {
    auto encoded =
        EncodePattern(credential, request.grid_size, user_id, &error);
    Zeroize(&credential);
    if (!encoded)
      return "ERROR: " + error;
    credential = std::move(*encoded);
  } else if (expected_type == kCredentialPin &&
             !std::all_of(
                 credential.begin(), credential.end(),
                 [](uint8_t value) { return value >= '0' && value <= '9'; })) {
    Zeroize(&credential);
    return "ERROR: PIN contains a non-digit byte";
  } else if (expected_type == kCredentialPassword && !managed_credential &&
             !std::all_of(
                 credential.begin(), credential.end(),
                 [](uint8_t value) { return value >= 32 && value <= 127; })) {
    Zeroize(&credential);
    return "ERROR: password contains a character Android lock credentials do "
           "not accept";
  }
  if (expected_type != kCredentialNone && credential.empty()) {
    return "ERROR: credential is empty";
  }
  if (expected_type != kCredentialNone && !managed_credential) {
    const auto delay = RemainingAuthDelay();
    if (delay > 0s) {
      return "ERROR: authentication delayed; retry after " +
             std::to_string(delay.count()) + " seconds";
    }
  }

  std::vector<uint8_t> stretched =
      StretchCredential(credential, protector->password_data);
  Zeroize(&credential);
  if (stretched.empty())
    return "ERROR: scrypt failed";
  std::optional<km::HardwareAuthToken> auth_token;
  bool credential_rejected = false;
  if (protector->password_data &&
      !protector->password_data->password_handle.empty() &&
      !VerifyGatekeeper(user_id, *protector->password_data, stretched,
                        &auth_token,
                        &credential_rejected, &error)) {
    Zeroize(&stretched);
    if (credential_rejected && !managed_credential) {
      const auto retry = RecordFailedAuth();
      error += "; software retry after " + std::to_string(retry.count()) +
               " seconds";
    }
    return "ERROR: " + error;
  }
  if (expected_type != kCredentialNone && !managed_credential)
    ClearAuthFailures();

  std::vector<uint8_t> sec_hash =
      PersonalizedHash("secdiscardable-transform", protector->secdiscardable);
  std::vector<uint8_t> protector_secret = stretched;
  protector_secret.insert(protector_secret.end(), sec_hash.begin(),
                          sec_hash.end());
  Zeroize(&stretched);
  Zeroize(&sec_hash);

  std::vector<uint8_t> inner;
  std::vector<uint8_t> sp;
  if (protector->version == 1) {
    auto software_key = PersonalizedHash("application-id", protector_secret);
    software_key.resize(32);
    inner = AesGcmDecrypt(software_key, protector->content);
    Zeroize(&software_key);
    if (!inner.empty())
      sp = DecryptProtectorLayer(protector->id, inner, auth_token, &error);
  } else if (protector->version == 2 || protector->version == 3) {
    inner = DecryptProtectorLayer(protector->id, protector->content, auth_token,
                                  &error);
    if (!inner.empty()) {
      auto software_key = PersonalizedHash("application-id", protector_secret);
      software_key.resize(32);
      sp = AesGcmDecrypt(software_key, inner);
      Zeroize(&software_key);
    }
  } else {
    error = "unsupported Synthetic Password version " +
            std::to_string(protector->version);
  }
  Zeroize(&inner);
  Zeroize(&protector_secret);
  if (sp.empty())
    return "ERROR: Synthetic Password decrypt failed: " + error;

  std::optional<km::HardwareAuthToken> user_auth_token;
  std::string user_auth_error;
  const bool unlock_unified_profiles =
      !managed_credential && user_id == 0 && HasLockedUnifiedProfiles();
  if (unlock_unified_profiles &&
      !GetSyntheticPasswordAuthToken(user_id, protector->version, sp,
                                     &user_auth_token, &user_auth_error)) {
    user_auth_token.reset();
  }

  std::vector<uint8_t> fbe_key = DeriveFbeKey(protector->version, sp);
  if (fbe_key.empty()) {
    Zeroize(&sp);
    return "ERROR: FBE key derivation failed";
  }
  const bool installed =
      RetrieveAndInstallKey(UserCeKeyDir(user_id), fbe_key, &error);
  Zeroize(&fbe_key);
  if (!installed) {
    Zeroize(&sp);
    return "ERROR: CE key install failed: " + error;
  }
  g_ce_users.insert(user_id);
  g_ce_ready = g_ce_users.contains(0);

  std::string profile_result;
  if (unlock_unified_profiles) {
    if (!user_auth_token) {
      profile_result = "WARNING: unified work profile: " + user_auth_error;
    } else {
      profile_result = UnlockUnifiedProfiles(user_id, user_auth_token);
    }
  }
  Zeroize(&sp);
  if (authorize_adb)
    SetAdbAuthorization("credential");
  std::string result = "OK: user " + std::to_string(user_id) +
                       " CE storage unlocked";
  if (!profile_result.empty())
    result += "; " + profile_result;
  return result;
}

const char *CredentialTypeName(int type) {
  switch (type) {
  case kCredentialNone:
    return "none";
  case kCredentialPattern:
    return "pattern";
  case kCredentialPin:
    return "pin";
  case kCredentialPassword:
    return "password";
  default:
    return "unknown";
  }
}

std::string Status() {
  std::ostringstream out;
  out << (g_de_ready ? "DE=mounted" : "DE=unavailable");
  out << ' ' << (g_ce_ready ? "CE=accessible" : "CE=locked");
  out << " ADB="
      << android::base::GetProperty("sys.recovery.adb.authorized", "locked");
  if (g_de_ready) {
    std::string error;
    auto protector = LoadProtector(0, &error);
    const int type = !protector
                         ? 0
                         : protector->password_data
                               ? protector->password_data->credential_type
                               : kCredentialNone;
    out << " credential=" << CredentialTypeName(type);
  }
  const int grid = ReadPatternGridSize(0);
  if (grid != 0)
    out << " pattern_grid=" << grid << 'x' << grid;
  out << " users=[";
  const std::vector<uint32_t> users = ListUsers();
  for (size_t i = 0; i < users.size(); ++i) {
    const uint32_t user_id = users[i];
    if (i != 0)
      out << ',';
    std::string error;
    auto protector = LoadProtector(user_id, &error);
    const int type = !protector
                         ? 0
                         : protector->password_data
                               ? protector->password_data->credential_type
                               : kCredentialNone;
    out << user_id << ':'
        << (g_de_users.contains(user_id) ? "DE" : "no-DE") << ':'
        << (g_ce_users.contains(user_id) ? "CE" : "locked") << ':'
        << CredentialTypeName(type);
    if (access(UnifiedProfileKeyFile(user_id).c_str(), F_OK) == 0)
      out << ":unified-profile";
  }
  out << ']';
  return out.str();
}

void AutoUnlockNone() {
  if (!g_de_ready)
    return;
  for (uint32_t user_id : ListUsers()) {
    if (!g_de_users.contains(user_id) || g_ce_users.contains(user_id))
      continue;
    std::string error;
    auto protector = LoadProtector(user_id, &error);
    const int stored_type = protector && protector->password_data
                                ? protector->password_data->credential_type
                                : kCredentialNone;
    if (protector && stored_type == kCredentialNone) {
      RequestHeader request{
          .magic = kRequestMagic,
          .kind = static_cast<uint32_t>(RequestKind::kNone),
          .user_id = user_id,
      };
      LOG(INFO) << Unlock(request, {}, false, true);
    }
  }
}

int RunDaemon() {
  android::base::InitLogging(nullptr, android::base::KernelLogger);
  SetAdbAuthorization("locked");
  ABinderProcess_setThreadPoolMaxThreadCount(1);
  ABinderProcess_startThreadPool();
  const std::string mount_result = MountData();
  LOG(INFO) << mount_result;
  AutoUnlockNone();
  int server = android_get_control_socket(kSocketName);
  if (server < 0 || listen(server, 4) != 0) {
    PLOG(ERROR) << "cannot listen on recovery CE socket";
    return 1;
  }
  pollfd server_poll{
      .fd = server,
      .events = POLLIN,
  };
  for (;;) {
    const int ready =
        TEMP_FAILURE_RETRY(poll(&server_poll, 1, g_de_ready ? -1 : 1000));
    if (ready == 0) {
      const std::string retry_result = MountData();
      if (g_de_ready) {
        LOG(INFO) << retry_result;
        AutoUnlockNone();
      }
      continue;
    }
    if (ready < 0)
      continue;
    int client =
        TEMP_FAILURE_RETRY(accept4(server, nullptr, nullptr, SOCK_CLOEXEC));
    if (client < 0)
      continue;
    RequestHeader request{};
    if (!ReadExact(client, &request, sizeof(request)) ||
        request.magic != kRequestMagic ||
        request.credential_size > kMaxCredentialBytes) {
      SendString(client, "ERROR: invalid request");
      close(client);
      continue;
    }
    std::vector<uint8_t> credential(request.credential_size);
    if (!ReadExact(client, credential.data(), credential.size())) {
      Zeroize(&credential);
      close(client);
      continue;
    }
    std::string response;
    switch (static_cast<RequestKind>(request.kind)) {
    case RequestKind::kStatus:
      response = Status();
      break;
    case RequestKind::kNone:
    case RequestKind::kPattern:
    case RequestKind::kPin:
    case RequestKind::kPassword:
      response = Unlock(request, std::move(credential), request.user_id == 0);
      break;
    case RequestKind::kMaintenanceChallenge:
      response = request.credential_size == 0
                     ? NewMaintenanceChallenge()
                     : "ERROR: invalid maintenance challenge request";
      break;
    case RequestKind::kMaintenanceResponse:
      response = VerifyMaintenanceResponse(credential);
      break;
    default:
      response = "ERROR: invalid request";
      break;
    }
    Zeroize(&credential);
    SendString(client, response);
    close(client);
  }
}

std::vector<uint8_t> ReadCredential(const char *prompt) {
  const bool tty = isatty(STDIN_FILENO);
  bool echo_disabled = false;
  termios old_settings{};
  if (tty && tcgetattr(STDIN_FILENO, &old_settings) == 0) {
    termios settings = old_settings;
    settings.c_lflag &= ~ECHO;
    tcsetattr(STDIN_FILENO, TCSAFLUSH, &settings);
    echo_disabled = true;
    std::cerr << prompt << std::flush;
  }
  std::string line;
  std::getline(std::cin, line);
  if (echo_disabled) {
    tcsetattr(STDIN_FILENO, TCSAFLUSH, &old_settings);
    std::cerr << '\n';
  }
  std::vector<uint8_t> result(line.begin(), line.end());
  if (!line.empty())
    OPENSSL_cleanse(line.data(), line.size());
  return result;
}

void PrintUsage() {
  std::cerr << "usage:\n"
            << "  haotian-recovery-decrypt status\n"
            << "  haotian-recovery-decrypt unlock [--user USER_ID] none\n"
            << "  haotian-recovery-decrypt unlock [--user USER_ID] pin\n"
            << "  haotian-recovery-decrypt unlock [--user USER_ID] password\n"
            << "  haotian-recovery-decrypt unlock [--user USER_ID] pattern "
               "[3|4|5|6]\n"
            << "  haotian-recovery-decrypt maintenance\n"
            << "USER_ID defaults to 0. Unified-challenge profiles are "
               "unlocked with user 0 automatically.\n"
            << "Credentials are read from stdin and never accepted in argv.\n"
            << "Pattern input is a comma-separated, zero-based cell sequence; "
               "e.g. 0,1,5,9.\n";
}

std::optional<std::string> SendDaemonRequest(RequestHeader request,
                                             std::vector<uint8_t> *payload) {
  if (payload->size() > kMaxCredentialBytes)
    return std::nullopt;
  request.credential_size = static_cast<uint32_t>(payload->size());
  int socket = socket_local_client(
      kSocketName, ANDROID_SOCKET_NAMESPACE_RESERVED, SOCK_STREAM);
  if (socket < 0)
    return std::nullopt;
  const bool sent = WriteExact(socket, &request, sizeof(request)) &&
                    WriteExact(socket, payload->data(), payload->size());
  Zeroize(payload);
  auto response = sent ? ReceiveString(socket) : std::nullopt;
  close(socket);
  return response;
}

std::optional<std::string> SendEmptyRequest(RequestKind kind) {
  RequestHeader request{
      .magic = kRequestMagic,
      .kind = static_cast<uint32_t>(kind),
  };
  std::vector<uint8_t> empty;
  return SendDaemonRequest(request, &empty);
}

bool ResponseSucceeded(const std::optional<std::string> &response) {
  return response && !android::base::StartsWith(*response, "ERROR:");
}

int EnterMaintenanceAuthentication() {
  auto challenge = SendEmptyRequest(RequestKind::kMaintenanceChallenge);
  if (!challenge) {
    std::cerr << "ERROR: recovery authentication daemon is unavailable\n";
    return 1;
  }
  std::cout << *challenge << '\n';
  if (!ResponseSucceeded(challenge))
    return 1;
  auto response = ReadCredential("Decrypted response: ");
  RequestHeader request{
      .magic = kRequestMagic,
      .kind = static_cast<uint32_t>(RequestKind::kMaintenanceResponse),
  };
  auto result = SendDaemonRequest(request, &response);
  if (!result) {
    std::cerr << "ERROR: recovery authentication daemon did not respond\n";
    return 1;
  }
  std::cout << *result << '\n';
  return ResponseSucceeded(result) ? 0 : 1;
}

std::optional<RequestKind> CredentialKindFromStatus(std::string_view status) {
  if (status.find("credential=none") != std::string_view::npos)
    return RequestKind::kNone;
  if (status.find("credential=pattern") != std::string_view::npos)
    return RequestKind::kPattern;
  if (status.find("credential=pin") != std::string_view::npos)
    return RequestKind::kPin;
  if (status.find("credential=password") != std::string_view::npos)
    return RequestKind::kPassword;
  return std::nullopt;
}

uint32_t PatternGridFromStatus(std::string_view status) {
  constexpr std::string_view marker = "pattern_grid=";
  const size_t at = status.find(marker);
  if (at == std::string_view::npos || at + marker.size() >= status.size())
    return 0;
  const char size = status[at + marker.size()];
  return size >= '3' && size <= '6' ? static_cast<uint32_t>(size - '0') : 0;
}

int RunAdbAuthenticator() {
  std::cout << "Haotian recovery ADB is locked.\n";
  auto status = SendEmptyRequest(RequestKind::kStatus);
  if (!status) {
    std::cerr << "Recovery authentication service is unavailable.\n"
                 "Full ADB access remains denied.\n";
    return 1;
  }

  bool use_maintenance = status->find("DE=mounted") == std::string::npos;
  if (use_maintenance) {
    std::cout << "User data cannot be decrypted or is damaged.\n"
                 "Device credentials cannot be used; ROM signing-key "
                 "maintenance authentication is required.\n";
  } else {
    std::cout << "1. Unlock CE with the device credential\n"
                 "2. Authorize maintenance with the ROM signing key\n"
                 "Select [1/2]: "
              << std::flush;
    std::string choice;
    std::getline(std::cin, choice);
    use_maintenance = choice == "2";
    if (choice != "1" && choice != "2") {
      std::cerr << "Invalid selection. Full ADB access remains denied.\n";
      return 1;
    }
  }

  int result = 1;
  if (use_maintenance) {
    result = EnterMaintenanceAuthentication();
  } else {
    auto kind = CredentialKindFromStatus(*status);
    if (!kind) {
      std::cerr << "Stored credential type is unavailable. Use ROM "
                   "signing-key maintenance authentication instead.\n";
      return 1;
    }
    RequestHeader request{
        .magic = kRequestMagic,
        .kind = static_cast<uint32_t>(*kind),
    };
    std::vector<uint8_t> credential;
    switch (*kind) {
    case RequestKind::kNone:
      break;
    case RequestKind::kPin:
      credential = ReadCredential("PIN: ");
      break;
    case RequestKind::kPassword:
      credential = ReadCredential("Password: ");
      break;
    case RequestKind::kPattern:
      request.grid_size = PatternGridFromStatus(*status);
      credential =
          ReadCredential("Pattern cells (zero-based, comma-separated): ");
      break;
    default:
      return 1;
    }
    auto response = SendDaemonRequest(request, &credential);
    if (!response) {
      std::cerr << "ERROR: recovery authentication daemon did not respond\n";
      return 1;
    }
    std::cout << *response << '\n';
    result = ResponseSucceeded(response) ? 0 : 1;
  }

  if (result != 0) {
    std::cerr << "Full ADB access remains denied.\n";
    return result;
  }
  std::cout << "Full recovery ADB access is now enabled.\n" << std::flush;
  execl("/system/bin/sh", "sh", nullptr);
  PLOG(ERROR) << "cannot start recovery shell";
  return 1;
}

int RunClient(int argc, char **argv) {
  RequestHeader request{.magic = kRequestMagic};
  std::vector<uint8_t> credential;
  if (argc == 2 && std::string_view(argv[1]) == "status") {
    request.kind = static_cast<uint32_t>(RequestKind::kStatus);
  } else if (argc >= 3 && std::string_view(argv[1]) == "unlock") {
    int type_at = 2;
    if (std::string_view(argv[type_at]) == "--user") {
      if (argc < 5) {
        PrintUsage();
        return 2;
      }
      const std::string_view user(argv[type_at + 1]);
      auto parsed = std::from_chars(user.data(), user.data() + user.size(),
                                    request.user_id, 10);
      if (parsed.ec != std::errc() || parsed.ptr != user.data() + user.size() ||
          !IsValidUserId(request.user_id)) {
        PrintUsage();
        return 2;
      }
      type_at += 2;
    }
    const std::string_view type(argv[type_at]);
    const int remaining = argc - type_at;
    const std::string user_label = " for user " +
                                   std::to_string(request.user_id) + ": ";
    if (type == "none" && remaining == 1) {
      request.kind = static_cast<uint32_t>(RequestKind::kNone);
    } else if (type == "pin" && remaining == 1) {
      request.kind = static_cast<uint32_t>(RequestKind::kPin);
      credential = ReadCredential(("PIN" + user_label).c_str());
    } else if (type == "password" && remaining == 1) {
      request.kind = static_cast<uint32_t>(RequestKind::kPassword);
      credential = ReadCredential(("Password" + user_label).c_str());
    } else if (type == "pattern" && (remaining == 1 || remaining == 2)) {
      request.kind = static_cast<uint32_t>(RequestKind::kPattern);
      if (remaining == 2) {
        const std::string_view grid(argv[type_at + 1]);
        auto parsed = std::from_chars(grid.data(), grid.data() + grid.size(),
                                      request.grid_size, 10);
        if (parsed.ec != std::errc() ||
            parsed.ptr != grid.data() + grid.size() || request.grid_size < 3 ||
            request.grid_size > 6) {
          PrintUsage();
          return 2;
        }
      }
      credential = ReadCredential(("Pattern cells" + user_label).c_str());
    } else {
      PrintUsage();
      return 2;
    }
  } else if (argc == 2 && std::string_view(argv[1]) == "maintenance") {
    return EnterMaintenanceAuthentication();
  } else {
    PrintUsage();
    return 2;
  }
  if (credential.size() > kMaxCredentialBytes) {
    Zeroize(&credential);
    std::cerr << "ERROR: credential input is too long\n";
    return 2;
  }
  auto response = SendDaemonRequest(request, &credential);
  if (!response) {
    std::cerr << "ERROR: recovery CE daemon did not respond\n";
    return 1;
  }
  std::cout << *response << '\n';
  return android::base::StartsWith(*response, "ERROR:") ? 1 : 0;
}

} // namespace

int main(int argc, char **argv) {
  if (argc == 2 && std::string_view(argv[1]) == "--daemon")
    return RunDaemon();
  if (argc == 2 && std::string_view(argv[1]) == "adb-auth")
    return RunAdbAuthenticator();
  return RunClient(argc, argv);
}
