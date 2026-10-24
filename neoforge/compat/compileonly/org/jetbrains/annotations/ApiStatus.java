// Compile-only stand-in for JetBrains annotations (not packaged).
package org.jetbrains.annotations;

import java.lang.annotation.*;

public final class ApiStatus {
    @Retention(RetentionPolicy.CLASS) public @interface Internal {}
    @Retention(RetentionPolicy.CLASS) public @interface Experimental {}
    @Retention(RetentionPolicy.CLASS) public @interface NonExtendable {}
    @Retention(RetentionPolicy.CLASS) public @interface OverrideOnly {}
    @Retention(RetentionPolicy.CLASS) public @interface ScheduledForRemoval { String inVersion() default ""; }
    @Retention(RetentionPolicy.CLASS) public @interface AvailableSince { String value(); }
}
