# Haotian Gallery video compatibility

Xiaomi Gallery 4.3 delegates video playback to a separate HyperOS application
using private actions such as `com.miui.videoplayer.LOCAL_VIDEO_PLAY`. The full
MediaViewer package is intentionally not shipped: it brings its own native
player/transcoder stack, MIUI-only APIs, casting integration and privileged
permissions merely to provide an operation Android already supports.

This no-permission, non-privileged trampoline accepts only those private video
actions with `content` or `file` video URIs. It converts them into a standard
`ACTION_VIEW`, preserves the temporary read grant, and directs playback to
Google Photos. If Photos is absent, Android's normal video resolver is used.

The component does not register for standard `VIEW` intents and therefore does
not change the default video handler for any other application.
