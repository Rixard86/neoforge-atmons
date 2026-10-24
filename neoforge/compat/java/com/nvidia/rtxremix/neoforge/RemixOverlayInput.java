// SPDX-License-Identifier: Apache-2.0
package com.nvidia.rtxremix.neoforge;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.Platform;
import org.lwjgl.system.SharedLibrary;
import org.lwjgl.system.windows.User32;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.lwjgl.system.MemoryUtil.memAddress;

/**
 * Gives RTX Remix's menus their mouse back.
 *
 * <p>The runtime reads menu input through raw input sent to an invisible overlay
 * window it creates ("RemixGuiInputSink"). A process has one raw-input target per
 * device type, and GLFW claims the mouse for Minecraft's window while the cursor is
 * locked and with "Raw Input" on, then removes the registration when the cursor is
 * unlocked. After the first time in a world the overlay gets no mouse input at all,
 * so an open menu cannot be used. Pointing the mouse back at the overlay once the
 * cursor is free fixes that; GLFW takes it again the next time the cursor locks.
 */
public final class RemixOverlayInput {
    private static final Logger LOGGER = LoggerFactory.getLogger(RemixOverlayInput.class);

    private static final String OVERLAY_CLASS = "RemixGuiInputSink";
    private static final short HID_USAGE_PAGE_GENERIC = 0x01;
    private static final short HID_USAGE_GENERIC_MOUSE = 0x02;
    private static final int RIDEV_INPUTSINK = 0x00000100;
    /** sizeof(RAWINPUTDEVICE) on 64-bit Windows: two USHORTs, a DWORD and an HWND. */
    private static final int RAWINPUTDEVICE_SIZE = 16;

    private static long findWindowEx;
    private static long getWindowThreadProcessId;
    private static long registerRawInputDevices;
    private static boolean warned;

    private RemixOverlayInput() {
    }

    /** Sends raw mouse input to the Remix overlay. Call after unlocking the cursor. */
    public static void reclaimRawMouse() {
        if (Platform.get() != Platform.WINDOWS) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (registerRawInputDevices == 0L) {
                SharedLibrary user32 = User32.getLibrary();
                findWindowEx = user32.getFunctionAddress("FindWindowExW");
                getWindowThreadProcessId = user32.getFunctionAddress("GetWindowThreadProcessId");
                registerRawInputDevices = user32.getFunctionAddress("RegisterRawInputDevices");
            }
            long overlay = findOwnOverlay(stack);
            if (overlay == 0L) {
                warnOnce("RTX Remix's menu input window was not found; its menus may not take the mouse");
                return;
            }
            ByteBuffer device = stack.calloc(8, RAWINPUTDEVICE_SIZE);
            device.putShort(0, HID_USAGE_PAGE_GENERIC)
                    .putShort(2, HID_USAGE_GENERIC_MOUSE)
                    .putInt(4, RIDEV_INPUTSINK)
                    .putLong(8, overlay);
            if (JNI.invokePI(memAddress(device), 1, RAWINPUTDEVICE_SIZE, registerRawInputDevices) == 0) {
                warnOnce("RegisterRawInputDevices failed for RTX Remix's menu input window");
            }
        } catch (RuntimeException e) {
            if (!warned) {
                warned = true;
                LOGGER.warn("Could not give RTX Remix's menus the mouse", e);
            }
        }
    }

    private static long findOwnOverlay(MemoryStack stack) {
        long className = memAddress(stack.UTF16(OVERLAY_CLASS));
        IntBuffer pid = stack.mallocInt(1);
        long ownPid = ProcessHandle.current().pid();
        long hwnd = 0L;
        while ((hwnd = JNI.invokePPPPP(0L, hwnd, className, 0L, findWindowEx)) != 0L) {
            JNI.invokePPI(hwnd, memAddress(pid), getWindowThreadProcessId);
            if (Integer.toUnsignedLong(pid.get(0)) == ownPid) {
                return hwnd;
            }
        }
        return 0L;
    }

    private static void warnOnce(String message) {
        if (!warned) {
            warned = true;
            LOGGER.warn(message);
        }
    }
}
