# Browser worker isolation

The worker is opt-in through the Compose `browser` profile. It runs as the image's
unprivileged user, with Chromium sandboxing enabled, a read-only root filesystem,
no host capabilities, no-new-privileges, private shared memory and bounded RAM,
CPU, process count and temporary storage. Browser operations have a deadline.
Failure to initialize the sandbox fails the request; there is no unsandboxed fallback.

`deploy/seccomp_profile.json` derives from Microsoft Playwright v1.64.0's Docker profile
(https://github.com/microsoft/playwright/blob/v1.64.0/utils/docker/seccomp_profile.json).
The Apache 2.0 license is included in `PLAYWRIGHT-LICENSE`. The added `chroot`
syscall permission permits Chromium's chroot inside its new user namespace when
all container host capabilities are dropped; it grants no host capability.

The host must support unprivileged user namespaces. Keep the worker internal and
up-to-date. This is defense in depth, not a guarantee that arbitrary browser
vulnerabilities cannot escape isolation. The transport independently validates
public destinations at socket DNS resolution, rejects writes and WebSockets, and
bounds redirects/subrequests and response sizes.
