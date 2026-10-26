// An import, not `java.time.Duration` inline: with the java plugin applied (kotlin("jvm") applies
// it), `java` resolves to the `java { }` extension, so the fully-qualified name doesn't compile.
// --8<-- [start:retention]
import java.time.Duration

serialkompat {
    history {
        depth.set(10)                          // only the newest 10 recorded versions
        // or:
        sinceVersion.set("2.0.0")              // only versions >= 2.0.0
        // or:
        maxAge.set(Duration.ofDays(548))       // only versions recorded in the last ~18 months
    }
}
// --8<-- [end:retention]
