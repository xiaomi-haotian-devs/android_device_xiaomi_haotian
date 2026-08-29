/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The stock camera provider is started with capabilities, so LD_PRELOAD is
 * ignored under AT_SECURE. Loading this library through DT_NEEDED moves the
 * real provider process after init has created its process group.
 */

#include <fcntl.h>
#include <stdio.h>
#include <unistd.h>

namespace {

constexpr char kProviderCgroup[] =
        "/sys/fs/cgroup/camera/provider/cgroup.procs";

__attribute__((constructor)) void MoveProviderToCameraCgroup() {
    const int fd = open(kProviderCgroup, O_WRONLY | O_CLOEXEC);
    if (fd < 0) {
        return;
    }

    char pid[32];
    const int length = snprintf(pid, sizeof(pid), "%d", getpid());
    if (length > 0) {
        (void)write(fd, pid, static_cast<size_t>(length));
    }
    close(fd);
}

}  // namespace
