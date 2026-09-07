/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

#include <binder/IBinder.h>
#include <binder/IServiceManager.h>
#include <binder/Parcel.h>
#include <utils/Errors.h>
#include <utils/String16.h>

#include <array>
#include <atomic>
#include <cerrno>
#include <chrono>
#include <cinttypes>
#include <csignal>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <map>
#include <optional>
#include <string>
#include <string_view>
#include <thread>
#include <unistd.h>

namespace {

using android::IBinder;
using android::IServiceManager;
using android::Parcel;
using android::sp;
using android::status_t;
using Clock = std::chrono::steady_clock;

constexpr uint32_t kRefreshDebugTransaction = 1048;
constexpr int32_t kWireVersion = 1;
constexpr std::string_view kDisplaySysfs = "/sys/class/mi_display/disp-DSI-0";
constexpr std::array<int, 11> kPanelRates = {1,  10, 24, 30,  40, 48,
                                             50, 60, 90, 120, 144};

std::atomic_bool gRunning = true;

struct Options {
  int intervalMs = 500;
  int64_t count = -1;
  bool csv = false;
};

struct SfSnapshot {
  int32_t ltpoState = -1;
  bool ltpoRuntimeEnabled = false;
  bool ltpoOverrideActive = false;
  int32_t modeId = -1;
  int32_t hwcId = -1;
  int32_t group = 0;
  int32_t width = 0;
  int32_t height = 0;
  float vsyncRate = 0.0f;
  float peakRate = 0.0f;
  float renderRate = 0.0f;
  int32_t policyDefaultMode = -1;
  float policyPhysicalMin = 0.0f;
  float policyPhysicalMax = 0.0f;
  float policyRenderMin = 0.0f;
  float policyRenderMax = 0.0f;
  bool allowGroupSwitching = false;
  uint64_t presentCount = 0;
  int32_t powerMode = -1;
  uint64_t activitySerial = 0;
};

struct PanelSnapshot {
  std::map<std::string, uint64_t, std::less<>> counters;
  std::optional<int> driverFps;
  std::optional<double> hardwareVsyncFps;
  std::optional<uint64_t> hardwareVsyncPeriodNs;
};

struct DdicRequest {
  const char *type = "unknown";
  int minFps = -1;
};

void signalHandler(int) { gRunning.store(false, std::memory_order_relaxed); }

void usage(const char *argv0) {
  std::fprintf(stdout,
               "Usage: %s [--interval-ms N] [--count N|--once] [--csv]\n"
               "\n"
               "Continuously samples the haotian display pipeline without "
               "creating a Surface,\n"
               "enabling an overlay, or registering a VSYNC receiver. Default "
               "interval: 500 ms.\n",
               argv0);
}

bool parsePositive(const char *value, int64_t *result) {
  if (!value || !*value)
    return false;
  errno = 0;
  char *end = nullptr;
  const long long parsed = std::strtoll(value, &end, 10);
  if (errno != 0 || *end != '\0' || parsed <= 0)
    return false;
  *result = parsed;
  return true;
}

std::optional<Options> parseOptions(int argc, char **argv) {
  Options options;
  for (int i = 1; i < argc; ++i) {
    const std::string_view arg(argv[i]);
    if (arg == "--help" || arg == "-h") {
      usage(argv[0]);
      std::exit(0);
    } else if (arg == "--csv") {
      options.csv = true;
    } else if (arg == "--once") {
      options.count = 1;
    } else if (arg == "--interval-ms" && i + 1 < argc) {
      int64_t parsed = 0;
      if (!parsePositive(argv[++i], &parsed) || parsed < 100 ||
          parsed > 60000) {
        std::fprintf(stderr, "Invalid interval; expected 100..60000 ms\n");
        return std::nullopt;
      }
      options.intervalMs = static_cast<int>(parsed);
    } else if (arg == "--count" && i + 1 < argc) {
      if (!parsePositive(argv[++i], &options.count)) {
        std::fprintf(stderr, "Invalid sample count\n");
        return std::nullopt;
      }
    } else {
      std::fprintf(stderr, "Unknown or incomplete option: %s\n", argv[i]);
      return std::nullopt;
    }
  }
  return options;
}

std::optional<std::string> readFile(std::string_view path) {
  const int fd =
      TEMP_FAILURE_RETRY(open(std::string(path).c_str(), O_RDONLY | O_CLOEXEC));
  if (fd < 0)
    return std::nullopt;

  std::string result;
  std::array<char, 4096> buffer{};
  while (true) {
    const ssize_t size =
        TEMP_FAILURE_RETRY(read(fd, buffer.data(), buffer.size()));
    if (size < 0) {
      close(fd);
      return std::nullopt;
    }
    if (size == 0)
      break;
    result.append(buffer.data(), static_cast<size_t>(size));
  }
  close(fd);
  return result;
}

std::optional<uint64_t> parseUnsigned(std::string_view value) {
  const std::string copy(value);
  errno = 0;
  char *end = nullptr;
  const unsigned long long parsed = std::strtoull(copy.c_str(), &end, 10);
  if (errno != 0 || end == copy.c_str())
    return std::nullopt;
  while (*end == ' ' || *end == '\t' || *end == '\r' || *end == '\n')
    ++end;
  if (*end != '\0')
    return std::nullopt;
  return static_cast<uint64_t>(parsed);
}

PanelSnapshot readPanelSnapshot() {
  PanelSnapshot snapshot;
  if (const auto contents =
          readFile(std::string(kDisplaySysfs) + "/disp_count")) {
    size_t begin = 0;
    while (begin < contents->size()) {
      const size_t end = contents->find('\n', begin);
      const std::string_view line(
          contents->data() + begin,
          (end == std::string::npos ? contents->size() : end) - begin);
      if (const size_t equals = line.find('=');
          equals != std::string_view::npos) {
        const auto key = line.substr(0, equals);
        if (const auto value = parseUnsigned(line.substr(equals + 1))) {
          snapshot.counters.emplace(key, *value);
        }
      }
      if (end == std::string::npos)
        break;
      begin = end + 1;
    }
  }

  if (const auto contents =
          readFile(std::string(kDisplaySysfs) + "/dynamic_fps")) {
    if (const auto value = parseUnsigned(*contents)) {
      snapshot.driverFps = static_cast<int>(*value);
    }
  }
  if (const auto contents =
          readFile(std::string(kDisplaySysfs) + "/hw_vsync_info")) {
    double fps = 0.0;
    uint64_t periodNs = 0;
    if (std::sscanf(contents->c_str(), "fps: %lf vsync_period_ns:%" SCNu64,
                    &fps, &periodNs) == 2) {
      snapshot.hardwareVsyncFps = fps;
      snapshot.hardwareVsyncPeriodNs = periodNs;
    }
  }
  return snapshot;
}

class SurfaceFlingerReader {
public:
  status_t connect() {
    const sp<IServiceManager> manager = android::defaultServiceManager();
    if (!manager)
      return android::NO_INIT;
    mSurfaceFlinger =
        manager->checkService(android::String16("SurfaceFlinger"));
    return mSurfaceFlinger ? android::OK : android::NAME_NOT_FOUND;
  }

  status_t read(SfSnapshot *snapshot) {
    if (!mSurfaceFlinger) {
      const status_t status = connect();
      if (status != android::OK)
        return status;
    }

    Parcel data;
    Parcel reply;
    data.writeInterfaceToken(android::String16("android.ui.ISurfaceComposer"));
    const status_t status =
        mSurfaceFlinger->transact(kRefreshDebugTransaction, data, &reply);
    if (status != android::OK) {
      mSurfaceFlinger.clear();
      return status;
    }

    const int32_t version = reply.readInt32();
    if (version != kWireVersion)
      return android::BAD_TYPE;
    snapshot->ltpoState = reply.readInt32();
    snapshot->ltpoRuntimeEnabled = reply.readInt32() != 0;
    snapshot->ltpoOverrideActive = reply.readInt32() != 0;
    snapshot->modeId = reply.readInt32();
    snapshot->hwcId = reply.readInt32();
    snapshot->group = reply.readInt32();
    snapshot->width = reply.readInt32();
    snapshot->height = reply.readInt32();
    snapshot->vsyncRate = reply.readFloat();
    snapshot->peakRate = reply.readFloat();
    snapshot->renderRate = reply.readFloat();
    snapshot->policyDefaultMode = reply.readInt32();
    snapshot->policyPhysicalMin = reply.readFloat();
    snapshot->policyPhysicalMax = reply.readFloat();
    snapshot->policyRenderMin = reply.readFloat();
    snapshot->policyRenderMax = reply.readFloat();
    snapshot->allowGroupSwitching = reply.readInt32() != 0;
    snapshot->presentCount = static_cast<uint64_t>(reply.readInt64());
    snapshot->powerMode = reply.readInt32();
    snapshot->activitySerial = static_cast<uint64_t>(reply.readInt64());
    return android::OK;
  }

private:
  sp<IBinder> mSurfaceFlinger;
};

uint64_t counterDelta(const PanelSnapshot &current,
                      const PanelSnapshot &previous, std::string_view key) {
  const auto currentIt = current.counters.find(key);
  const auto previousIt = previous.counters.find(key);
  if (currentIt == current.counters.end() ||
      previousIt == previous.counters.end() ||
      currentIt->second < previousIt->second) {
    return 0;
  }
  return currentIt->second - previousIt->second;
}

const char *ltpoStateName(int32_t state) {
  switch (state) {
  case 0:
    return "active";
  case 1:
    return "idle10";
  case 2:
    return "idle1";
  case 3:
    return "aod1";
  case 4:
    return "video";
  default:
    return "unknown";
  }
}

DdicRequest decodeDdicRequest(bool sfAvailable, int32_t group, int32_t ltpoState) {
  if (!sfAvailable)
    return {};

  const int32_t type = (group >> 24) & 0xff;
  if (type == 0x01)
    return {.type = ltpoState == 3 ? "aod" : "idle",
            .minFps = (group >> 8) & 0xff};
  if (type == 0x02)
    return {.type = "auto", .minFps = -1};
  return {.type = "fixed", .minFps = -1};
}

void printCsvHeader() {
  std::printf(
      "time_ms,sf_available,ltpo_state,ltpo_enabled,ltpo_override,mode_id,hwc_"
      "id,group,"
      "render_target_hz,"
      "sf_present_fps,mode_vsync_hz,mode_peak_hz,driver_reported_hz,ddic_"
      "request,ddic_requested_min_hz,ddic_closed_bucket_hz");
  std::printf(",hardware_vsync_hz,hardware_vsync_period_ns");
  for (const int rate : kPanelRates)
    std::printf(",ddic_%d_delta", rate);
  std::printf(",auto60_delta,normal60_delta,qsync60_delta,auto120_delta,"
              "normal120_delta,"
              "policy_default_mode,policy_physical_min,policy_physical_max,"
              "policy_render_min,"
              "policy_render_max,power_mode,activity_serial\n");
}

void printSample(bool csv, bool sfAvailable, int64_t elapsedMs,
                 const SfSnapshot &sf, float sfPresentFps,
                 const PanelSnapshot &panel,
                 const PanelSnapshot *previousPanel) {
  const DdicRequest ddicRequest =
      decodeDdicRequest(sfAvailable, sf.group, sf.ltpoState);
  std::array<uint64_t, kPanelRates.size()> deltas{};
  int closedDdicRate = -1;
  uint64_t largestDelta = 0;
  for (size_t i = 0; i < kPanelRates.size(); ++i) {
    const std::string key = "fps" + std::to_string(kPanelRates[i]) + "_times";
    deltas[i] = previousPanel ? counterDelta(panel, *previousPanel, key) : 0;
    if (deltas[i] > largestDelta) {
      largestDelta = deltas[i];
      closedDdicRate = kPanelRates[i];
    }
  }
  const auto subtypeDelta = [&](std::string_view key) {
    return previousPanel ? counterDelta(panel, *previousPanel, key) : 0;
  };
  const int driverFps = panel.driverFps.value_or(-1);
  const double hardwareVsyncFps = panel.hardwareVsyncFps.value_or(-1.0);
  const uint64_t hardwareVsyncPeriodNs =
      panel.hardwareVsyncPeriodNs.value_or(0);

  if (csv) {
    std::printf("%" PRId64
                ",%d,%s,%d,%d,%d,%d,0x%08x,%.3f,%.3f,%.3f,%.3f,%d,%s,%d,"
                "%d,%.3f,%" PRIu64,
                elapsedMs, sfAvailable, ltpoStateName(sf.ltpoState),
                sf.ltpoRuntimeEnabled, sf.ltpoOverrideActive, sf.modeId,
                sf.hwcId, static_cast<uint32_t>(sf.group), sf.renderRate,
                sfPresentFps, sf.vsyncRate, sf.peakRate, driverFps,
                ddicRequest.type, ddicRequest.minFps, closedDdicRate,
                hardwareVsyncFps, hardwareVsyncPeriodNs);
    for (const uint64_t delta : deltas)
      std::printf(",%" PRIu64, delta);
    std::printf(
        ",%" PRIu64 ",%" PRIu64 ",%" PRIu64 ",%" PRIu64 ",%" PRIu64
        ",%d,%.3f,%.3f,%.3f,%.3f,%d,%" PRIu64 "\n",
        subtypeDelta("Autofps60_times"), subtypeDelta("Normalfps60_times"),
        subtypeDelta("Qsyncfps60_times"), subtypeDelta("Autofps120_times"),
        subtypeDelta("Normalfps120_times"), sf.policyDefaultMode,
        sf.policyPhysicalMin, sf.policyPhysicalMax, sf.policyRenderMin,
        sf.policyRenderMax, sf.powerMode, sf.activitySerial);
    return;
  }

  if (sfAvailable) {
    std::printf(
        "t=%7" PRId64 "ms state=%-6s enabled=%d override=%d sf_target=%6.2fHz "
        "sf_present=%6.2ffps mode=%d/hwc%d group=0x%08x nominal=%6.2fHz "
        "hw_te=%6.2fHz driver=%3dHz ddic_req=%s",
        elapsedMs, ltpoStateName(sf.ltpoState), sf.ltpoRuntimeEnabled,
        sf.ltpoOverrideActive, sf.renderRate, sfPresentFps, sf.modeId, sf.hwcId,
        static_cast<uint32_t>(sf.group), sf.vsyncRate, hardwareVsyncFps,
        driverFps, ddicRequest.type);
    if (ddicRequest.minFps > 0)
      std::printf("/%dHz", ddicRequest.minFps);
  } else {
    std::printf("t=%7" PRId64
                "ms sf=unavailable hw_te=%6.2fHz driver=%3dHz ddic_req=unknown",
                elapsedMs, hardwareVsyncFps, driverFps);
  }

  if (closedDdicRate >= 0)
    std::printf(" ddic_closed=%3dHz", closedDdicRate);
  else
    std::printf(" ddic_closed=none");

  bool printedResidence = false;
  for (size_t i = 0; i < kPanelRates.size(); ++i) {
    if (deltas[i] == 0)
      continue;
    std::printf("%s%d:+%" PRIu64, printedResidence ? "," : " residence=[",
                kPanelRates[i], deltas[i]);
    printedResidence = true;
  }
  if (printedResidence)
    std::printf("]");
  std::printf("\n");
}

} // namespace

int main(int argc, char **argv) {
  const auto options = parseOptions(argc, argv);
  if (!options) {
    usage(argv[0]);
    return 2;
  }

  std::signal(SIGINT, signalHandler);
  std::signal(SIGTERM, signalHandler);
  setvbuf(stdout, nullptr, _IOLBF, 0);

  SurfaceFlingerReader sfReader;
  status_t lastSfError = android::OK;
  SfSnapshot previousSf;
  PanelSnapshot previousPanel;
  bool havePreviousSf = false;
  bool havePreviousPanel = false;
  const auto startedAt = Clock::now();
  auto previousSfAt = startedAt;
  auto nextSfAttemptAt = startedAt;

  if (options->csv) {
    printCsvHeader();
  } else {
    std::printf(
        "# sf_target=SF policy cadence; sf_present=completed SF presents; "
        "nominal=HWC mode TE; hw_te=kernel VSYNC measurement; "
        "driver=dynamic_fps (not actual in idle groups); "
        "ddic_req=current vendor group request; "
        "ddic_closed=largest disp_count bucket settled since the last "
        "sample\n");
  }

  int64_t samples = 0;
  while (gRunning.load(std::memory_order_relaxed) &&
         (options->count < 0 || samples < options->count)) {
    const auto now = Clock::now();
    const auto panel = readPanelSnapshot();
    SfSnapshot sf;
    status_t sfStatus = lastSfError;
    if (now >= nextSfAttemptAt) {
      sfStatus = sfReader.read(&sf);
      if (sfStatus != android::OK) {
        nextSfAttemptAt = now + std::chrono::seconds(5);
      }
    }
    float sfPresentFps = 0.0f;
    if (sfStatus != android::OK) {
      if (sfStatus != lastSfError) {
        std::fprintf(
            stderr,
            "SurfaceFlinger snapshot unavailable (%d); continuing with kernel "
            "data. A matching userdebug/eng framework transaction 1048 is "
            "required.\n",
            sfStatus);
      }
      lastSfError = sfStatus;
    } else {
      lastSfError = android::OK;
      const double seconds =
          havePreviousSf
              ? std::chrono::duration<double>(now - previousSfAt).count()
              : 0.0;
      if (havePreviousSf && seconds > 0.0 &&
          sf.presentCount >= previousSf.presentCount) {
        sfPresentFps = static_cast<float>(
            (sf.presentCount - previousSf.presentCount) / seconds);
      }
      previousSf = sf;
      previousSfAt = now;
      havePreviousSf = true;
    }

    const int64_t elapsedMs =
        std::chrono::duration_cast<std::chrono::milliseconds>(now - startedAt)
            .count();
    printSample(options->csv, sfStatus == android::OK, elapsedMs, sf,
                sfPresentFps, panel,
                havePreviousPanel ? &previousPanel : nullptr);
    previousPanel = panel;
    havePreviousPanel = true;
    ++samples;

    if (gRunning.load(std::memory_order_relaxed) &&
        (options->count < 0 || samples < options->count)) {
      std::this_thread::sleep_for(
          std::chrono::milliseconds(options->intervalMs));
    }
  }
  return lastSfError == android::OK ? 0 : 1;
}
