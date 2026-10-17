package br.com.cofre.desktop;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class InactivityLockTest {
    @Test
    void locksOnceAfterInactivityAndActivityRestartsDeadline() {
        var time = new AtomicLong();
        var locks = new AtomicInteger();
        var idle = new InactivityLock(time::get, locks::incrementAndGet);
        idle.configure(5);
        idle.start();
        time.set(TimeUnit.SECONDS.toNanos(4));
        idle.check();
        assertEquals(0, locks.get());
        idle.activity();
        time.set(TimeUnit.SECONDS.toNanos(8));
        idle.check();
        assertEquals(0, locks.get());
        time.set(TimeUnit.SECONDS.toNanos(9));
        idle.check();
        idle.check();
        assertEquals(1, locks.get());
    }

    @Test
    void disabledStoppedAndNewSessionDoNotReuseOldDeadline() {
        var time = new AtomicLong();
        var locks = new AtomicInteger();
        var idle = new InactivityLock(time::get, locks::incrementAndGet);
        idle.configure(0);
        idle.start();
        time.set(TimeUnit.DAYS.toNanos(1));
        idle.check();
        assertEquals(0, locks.get());
        idle.configure(1);
        idle.stop();
        time.addAndGet(TimeUnit.SECONDS.toNanos(2));
        idle.check();
        assertEquals(0, locks.get());
        idle.start();
        idle.check();
        assertEquals(0, locks.get());
        time.addAndGet(TimeUnit.SECONDS.toNanos(1));
        idle.check();
        assertEquals(1, locks.get());
    }
}
