// SPDX-License-Identifier: Apache-2.0
package com.nvidia.rtxremix.neoforge;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.windows.User32;
import org.lwjgl.system.windows.WNDCLASSEX;
import org.lwjgl.system.windows.WindowProc;
import org.lwjgl.system.windows.WindowsLibrary;

/**
 * The window RTX Remix presents into: a child of Minecraft's window covering its
 * client area.
 *
 * <p>Remix presents through a Vulkan swapchain, and Khronos leaves one window used by
 * two graphics APIs undefined. Where NVIDIA's OpenGL holds Minecraft's window, the
 * driver refuses Remix's swapchain ({@code VK_ERROR_NATIVE_WINDOW_IN_USE_KHR}) and
 * startup fails. A new window that no API has touched avoids that whatever the
 * driver's present method. It is disabled and transparent to hit testing, so mouse
 * and keyboard input still reach Minecraft's window; Remix's own menus take input
 * through the overlay window the runtime creates for them.
 *
 * <p>{@code -Drtxremix.neoforge.presentWindow=main} presents into Minecraft's window
 * as upstream does.
 */
public final class RemixPresentWindow {
    private static final String CLASS_NAME = "RtxRemixPresentWindow";

    private static final int WS_CHILD = 0x40000000;
    private static final int WS_VISIBLE = 0x10000000;
    private static final int WS_DISABLED = 0x08000000;
    private static final int WS_CLIPSIBLINGS = 0x04000000;
    private static final int WS_EX_NOPARENTNOTIFY = 0x00000004;
    private static final int WM_NCHITTEST = 0x0084;
    private static final long HTTRANSPARENT = -1L;
    private static final int SW_HIDE = 0;
    private static final int SWP_NOMOVE = 0x0002;
    private static final int SWP_NOZORDER = 0x0004;
    private static final int SWP_NOACTIVATE = 0x0010;
    private static final int SWP_NOCOPYBITS = 0x0100;

    /** The window class points at this for the life of the process. */
    private static WindowProc windowProc;
    private static int width = -1;
    private static int height = -1;

    private RemixPresentWindow() {
    }

    /** Whether Remix gets its own window rather than Minecraft's. */
    public static boolean enabled() {
        return !"main".equalsIgnoreCase(System.getProperty("rtxremix.neoforge.presentWindow", "child"));
    }

    /** Creates the window over {@code parent}'s client area. Render thread only. */
    public static long create(long parent, int clientWidth, int clientHeight) {
        long instance = WindowsLibrary.HINSTANCE;
        if (windowProc == null) {
            // Hit tests pass through to Minecraft's window, which sits under this one
            // in the same thread; everything else is the default.
            WindowProc proc = WindowProc.create((hwnd, message, wParam, lParam) -> message == WM_NCHITTEST
                    ? HTTRANSPARENT
                    : User32.DefWindowProc(hwnd, message, wParam, lParam));
            try (MemoryStack stack = MemoryStack.stackPush()) {
                WNDCLASSEX windowClass = WNDCLASSEX.calloc(stack)
                        .cbSize(WNDCLASSEX.SIZEOF)
                        .lpfnWndProc(proc)
                        .hInstance(instance)
                        .lpszClassName(stack.UTF16(CLASS_NAME));
                if (User32.RegisterClassEx(windowClass) == 0) {
                    proc.free();
                    throw new IllegalStateException("RegisterClassEx failed for " + CLASS_NAME);
                }
            }
            windowProc = proc;
        }
        width = Math.max(1, clientWidth);
        height = Math.max(1, clientHeight);
        long hwnd = User32.CreateWindowEx(WS_EX_NOPARENTNOTIFY, CLASS_NAME, "RTX Remix",
                WS_CHILD | WS_VISIBLE | WS_DISABLED | WS_CLIPSIBLINGS,
                0, 0, width, height, parent, 0L, instance, 0L);
        if (hwnd == 0L) {
            throw new IllegalStateException("CreateWindowEx failed for the RTX Remix present window");
        }
        return hwnd;
    }

    /**
     * Keeps the window the size of Minecraft's client area. Render thread only; does
     * nothing, and allocates nothing, while the size is unchanged or minimized.
     */
    public static void followClientSize(long hwnd, int clientWidth, int clientHeight) {
        if (clientWidth <= 0 || clientHeight <= 0 || (clientWidth == width && clientHeight == height)) {
            return;
        }
        width = clientWidth;
        height = clientHeight;
        User32.SetWindowPos(hwnd, 0L, 0, 0, clientWidth, clientHeight,
                SWP_NOMOVE | SWP_NOZORDER | SWP_NOACTIVATE | SWP_NOCOPYBITS);
    }

    /** Uncovers Minecraft's own window, for when Remix did not start. */
    public static void hide(long hwnd) {
        User32.ShowWindow(hwnd, SW_HIDE);
    }
}
