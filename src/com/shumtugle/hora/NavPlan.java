package com.shumtugle.hora;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * When to speak about the manoeuvres of one route. Plain Java, so it can be
 * tried on a desk against a recorded walk before it goes to the phone.
 *
 * A manoeuvre is known by its arrow. A navigator's card does not hold still:
 * it may flick from the next turn to "straight on" and back within seconds,
 * and its street line may change back and forth. So what was said is kept per
 * arrow for a while, and an arrow that comes back is the same manoeuvre, not a
 * new one, unless its distance grew (a new turn the same way) or it was gone
 * long enough. Each manoeuvre is said twice at most: early, when the distance
 * falls to about a minute's way (100 to 800 m), and at the turn (about eight
 * seconds' way, 15 to 150 m). The speed is told by how fast the distance melts.
 */
final class NavPlan {
    static final int QUIET = 0;
    static final int EARLY = 1;
    static final int NOW = 2;
    /** A turn with no distance to time it by. */
    static final int NO_DISTANCE = 3;

    /** On foot, until the distance shows otherwise; metres a second. */
    static final float WALK = 1.4f;
    private static final float SLOWEST = 0.5f;
    private static final float FASTEST = 40f;
    /** What was said about an arrow is kept this long after it was last seen. */
    static final long MEMORY_MS = 120_000;
    /**
     * A distance that grows by more than this, or by more than a quarter of itself (a rounded
     * "0.8 km" may read "0.9 km" next), is a new manoeuvre, even with the same arrow.
     */
    private static final float JUMP_M = 30f;
    private static final float JUMP_SHARE = 0.25f;
    /**
     * Once a turn has been said at the turn, it is behind: its distance growing by more than two
     * of the navigator's steps means the next turn the same way (one step may be the person
     * overshooting). An arrow that comes back from another one farther by as much also stands
     * for a turn further on.
     */
    private static final float JUMP_PASSED_M = 20f;
    private static final float JUMP_RETURNED_M = 20f;
    /** The speed is judged over at most this much of the latest way, and no less than the shortest. */
    private static final long SPEED_WINDOW_MS = 30_000;
    private static final long SPEED_SHORTEST_MS = 4_000;
    private static final int SAMPLES = 32;

    /** One manoeuvre: what was said about it, and how its distance ran. */
    private static final class Memo {
        final int id;
        boolean early;
        boolean now;
        long seenAt;
        float distance = -1;
        final long[] times = new long[SAMPLES];
        final float[] distances = new float[SAMPLES];
        int count;

        Memo(int id) {
            this.id = id;
        }
    }

    /** The speed, shared by all routes: it changes with the way of travel, not with the route. */
    private static float speed = WALK;

    private final Map<String, Memo> memos = new HashMap<String, Memo>();
    private Memo current;
    /** Manoeuvres are numbered across routes, so a late phrase of an old route never fits a new one. */
    private static int nextId;

    /** After update(): the manoeuvre the answer belongs to, and the distance to say, rounded. */
    int id;
    int metres;
    /** After update(): whether this state began a manoeuvre not seen before. */
    boolean fresh;

    /**
     * A new state of the card: its arrow (a fingerprint), what the arrow was read as, and the
     * distance in metres (-1 when none). Returns QUIET, EARLY, NOW or NO_DISTANCE.
     */
    int update(long now, String picture, int kind, float distance) {
        // The arrow on the card is still seen however long the card holds still; the others are
        // forgotten some time after they left it.
        Iterator<Map.Entry<String, Memo>> it = memos.entrySet().iterator();
        while (it.hasNext()) {
            Memo old = it.next().getValue();
            if (old != current && now - old.seenAt > MEMORY_MS) {
                it.remove();
            }
        }
        Memo m = memos.get(picture);
        boolean grew = false;
        if (m != null && distance >= 0 && m.distance >= 0) {
            // A quarter of the distance always counts as noise: far off, a navigator rounds by
            // a tenth of a kilometre, and "0.8 km" may read "0.9 km" next.
            float step = Math.max(m.now ? JUMP_PASSED_M : m != current ? JUMP_RETURNED_M : JUMP_M,
                    JUMP_SHARE * m.distance);
            grew = distance > m.distance + step;
        }
        fresh = m == null || grew;
        if (fresh) {
            m = new Memo(++nextId);
            memos.put(picture, m);
        }
        if (current != null && current != m) {
            // The arrow that leaves the card is last seen now.
            current.seenAt = now;
        }
        m.seenAt = now;
        current = m;
        id = m.id;
        if (distance >= 0) {
            learnSpeed(m, now, distance);
            m.distance = distance;
        }
        if (!NavArrow.spoken(kind)) {
            return QUIET;
        }
        if (distance < 0) {
            return NO_DISTANCE;
        }
        float nowAt = nowAt();
        float earlyAt = earlyAt();
        if (!m.now && distance <= nowAt) {
            m.now = true;
            m.early = true;
            return NOW;
        }
        if (!m.early && distance <= earlyAt) {
            m.early = true;
            metres = round(distance);
            return EARLY;
        }
        return QUIET;
    }

    /** How near the turn is said at the turn: about eight seconds' way, 15 to 150 m. */
    static float nowAt() {
        return clamp(speed * 8f, 15f, 150f);
    }

    /** How near the turn is said early: about a minute's way, 100 to 800 m. */
    static float earlyAt() {
        return clamp(speed * 60f, 100f, 800f);
    }

    /** Whether the manoeuvre on the card has had its early phrase (or its last one). */
    boolean saidEarly() {
        return current != null && current.early;
    }

    /** Whether the manoeuvre is the one on the card now. */
    boolean current(int manoeuvre) {
        return current != null && current.id == manoeuvre;
    }

    /** A phrase was not said after all (it came too late): it may be said when its arrow is back. */
    void unsay(int manoeuvre, boolean early) {
        for (Memo m : memos.values()) {
            if (m.id == manoeuvre) {
                if (early) {
                    // An early phrase that lost its place is not wanted once the turn itself was said.
                    if (!m.now) {
                        m.early = false;
                    }
                } else {
                    m.now = false;
                }
            }
        }
    }

    static float speed() {
        return speed;
    }

    /** Up to 100 m by tens, up to 300 m by fifties, then by hundreds. */
    static int round(float m) {
        if (m < 95) {
            return Math.max(10, Math.round(m / 10f) * 10);
        }
        if (m < 300) {
            return Math.max(100, Math.round(m / 50f) * 50);
        }
        return Math.round(m / 100f) * 100;
    }

    /** The speed, from how fast the distance to this manoeuvre melted over the last half minute. */
    private static void learnSpeed(Memo r, long now, float distance) {
        int i = r.count % SAMPLES;
        r.times[i] = now;
        r.distances[i] = distance;
        r.count++;
        int n = Math.min(r.count, SAMPLES);
        long oldestAt = now;
        float oldest = distance;
        for (int k = 0; k < n; k++) {
            if (now - r.times[k] <= SPEED_WINDOW_MS && r.times[k] < oldestAt) {
                oldestAt = r.times[k];
                oldest = r.distances[k];
            }
        }
        long span = now - oldestAt;
        if (span < SPEED_SHORTEST_MS || oldest <= distance) {
            return;
        }
        float v = (oldest - distance) / (span / 1000f);
        speed = clamp(0.7f * speed + 0.3f * v, SLOWEST, FASTEST);
    }

    /** For the desk: starts the speed afresh. */
    static void resetSpeed() {
        speed = WALK;
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
