package com.shumtugle.hora;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Reads a turn arrow by its shape, not by its pixels: a navigator may redraw
 * its arrows with any update, but an arrow stays a stroke that ends in a head
 * of two barbs, and the head looks where the turn goes.
 *
 * The picture comes as a small square mask (white stroke on nothing). The
 * reader finds where strokes end, picks the pair of ends that are a head's
 * barbs (as far along the stroke as each other, the shaft between them, the
 * shape its own mirror image across the axis), and tells the turn by the
 * angle of that axis. With no head, a closed hole in the shape means a pin,
 * the end of the route. Anything else is "unknown": for a hint in the ear,
 * not knowing is better than a confident wrong side.
 *
 * Plain Java on purpose, so the same code is tried on a desk against real
 * arrows before it goes to the phone.
 */
final class NavArrow {
    static final int NONE = 0;
    static final int UNKNOWN = 1;
    static final int STRAIGHT = 2;
    static final int SLIGHT_RIGHT = 3;
    static final int RIGHT = 4;
    static final int SHARP_RIGHT = 5;
    static final int BACK_RIGHT = 6;
    static final int SLIGHT_LEFT = 7;
    static final int LEFT = 8;
    static final int SHARP_LEFT = 9;
    static final int BACK_LEFT = 10;
    static final int ARRIVE = 11;

    /** Names for the journal and the desk test; never spoken. */
    static final String[] NAMES = {"none", "unknown", "straight", "slight right", "right", "sharp right",
        "back right", "slight left", "left", "sharp left", "back left", "arrive"};

    /** The side of the mask the reader works on. */
    static final int G = 64;

    /** Neighbours with their chamfer weights: 2 across a side, 3 across a corner. */
    private static final int[][] N8 = {{-1, -1, 3}, {0, -1, 2}, {1, -1, 3}, {1, 0, 2}, {1, 1, 3}, {0, 1, 2},
        {-1, 1, 3}, {-1, 0, 2}};
    private static final int FAR = 1 << 30;
    private static final int MAX_ENDS = 12;

    private NavArrow() {
    }

    /**
     * The mask of a picture given as ARGB pixels of any size: brought to G by G cells by
     * area (each cell averages the pixels it covers, weighted by how much of each it covers),
     * then a cell is the stroke when it is opaque enough and light enough. The scaling is
     * done here rather than by the platform, so the phone sees what the desk test saw.
     */
    static boolean[] mask(int[] argb, int w, int h) {
        boolean[] m = new boolean[G * G];
        double sx = w / (double) G;
        double sy = h / (double) G;
        for (int cy = 0; cy < G; cy++) {
            double y0 = cy * sy;
            double y1 = y0 + sy;
            for (int cx = 0; cx < G; cx++) {
                double x0 = cx * sx;
                double x1 = x0 + sx;
                double area = 0;
                double alpha = 0;
                double light = 0;
                for (int py = (int) y0; py < Math.min(h, Math.ceil(y1)); py++) {
                    double wy = Math.min(y1, py + 1) - Math.max(y0, py);
                    if (wy <= 0) {
                        continue;
                    }
                    for (int px = (int) x0; px < Math.min(w, Math.ceil(x1)); px++) {
                        double wx = Math.min(x1, px + 1) - Math.max(x0, px);
                        if (wx <= 0) {
                            continue;
                        }
                        int p = argb[py * w + px];
                        double a = (p >>> 24) / 255.0;
                        double l = ((p >> 16 & 255) + (p >> 8 & 255) + (p & 255)) / 3.0;
                        area += wx * wy;
                        alpha += wx * wy * a;
                        light += wx * wy * a * l;
                    }
                }
                if (area <= 0 || alpha <= 0) {
                    continue;
                }
                double meanAlpha = alpha / area * 255;
                double meanLight = light / alpha;
                m[cy * G + cx] = meanAlpha > 96 && meanLight > 64;
            }
        }
        return m;
    }

    /** True for the kinds that are a turn or the end, which are said aloud. */
    static boolean spoken(int kind) {
        return kind != NONE && kind != STRAIGHT;
    }

    /**
     * Reads a mask of G by G cells, row after row, true where the stroke is.
     * Returns one of the kinds above.
     */
    static int read(boolean[] mask) {
        boolean[] body = largestPiece(mask);
        int size = 0;
        for (boolean b : body) {
            if (b) {
                size++;
            }
        }
        if (size < 20) {
            return NONE;
        }
        // How thick the stroke is: the farthest any cell lies from the shape's edge.
        List<Integer> edge = new ArrayList<Integer>();
        for (int p = 0; p < G * G; p++) {
            if (body[p] && onEdge(body, p)) {
                edge.add(p);
            }
        }
        int[] inner = along(body, edge, null);
        int width = 2;
        for (int p = 0; p < G * G; p++) {
            if (body[p]) {
                width = Math.max(width, inner[p]);
            }
        }
        int holes = holes(body);
        // The arrow comes in from below: the lowest cell, nearest the middle.
        int low = -1;
        for (int p = 0; p < G * G; p++) {
            if (!body[p]) {
                continue;
            }
            if (low < 0 || y(p) > y(low)
                    || y(p) == y(low) && Math.abs(x(p) - G / 2f) < Math.abs(x(low) - G / 2f)) {
                low = p;
            }
        }
        int[] d = along(body, java.util.Collections.singletonList(low), null);
        int r = Math.max(2, (int) (width * 0.8));
        List<Integer> tips = new ArrayList<Integer>();
        for (int p = 0; p < G * G; p++) {
            if (body[p] && d[p] > 2 * width && isEnd(d, p, r)) {
                tips.add(p);
            }
        }
        // A clean arrow has a handful of ends; dozens mean noise or a drawing, not an arrow.
        if (tips.size() > MAX_ENDS) {
            return UNKNOWN;
        }
        double bestSym = -1;
        int bestE1 = -1;
        int bestE2 = -1;
        double bestNx = 0;
        double bestNy = 0;
        int bestMid = -1;
        // Distances and ways back from every end: a pair is judged the same whichever barb comes first.
        int[][] fromTip = new int[tips.size()][];
        int[][] backTo = new int[tips.size()][];
        for (int i = 0; i < tips.size(); i++) {
            backTo[i] = new int[G * G];
            fromTip[i] = along(body, java.util.Collections.singletonList(tips.get(i)), backTo[i]);
        }
        for (int i = 0; i < tips.size(); i++) {
            int e1 = tips.get(i);
            int[] de = fromTip[i];
            for (int j = i + 1; j < tips.size(); j++) {
                int e2 = tips.get(j);
                double gap = Math.hypot(x(e1) - x(e2), y(e1) - y(e2));
                if (gap < width || gap > 0.8 * G) {
                    continue;
                }
                // Barbs are reached about equally far along the stroke.
                if (Math.abs(d[e1] - d[e2]) > 0.25 * Math.max(d[e1], d[e2])) {
                    continue;
                }
                // The shaft runs between the barbs: their middle lies on the shape.
                double mx = (x(e1) + x(e2)) / 2.0;
                double my = (y(e1) + y(e2)) / 2.0;
                int midX = (int) Math.rint(mx);
                int midY = (int) Math.rint(my);
                if (!nearBody(body, midX, midY)) {
                    continue;
                }
                // And the two meet close by, at the point, not around the whole shape.
                if (de[e2] / 2.0 > 1.8 * gap + width) {
                    continue;
                }
                // Across the barbs; the point is the side the shaft was heading to, farther along.
                double nx = -(y(e2) - y(e1)) / gap;
                double ny = (x(e2) - x(e1)) / gap;
                double fwd = 0;
                int nf = 0;
                double bwd = 0;
                int nb = 0;
                for (int q = 0; q < G * G; q++) {
                    if (!body[q] || Math.hypot(x(q) - mx, y(q) - my) >= gap / 2) {
                        continue;
                    }
                    double s = (x(q) - mx) * nx + (y(q) - my) * ny;
                    if (s > width / 2.0) {
                        fwd += d[q];
                        nf++;
                    } else if (s < -width / 2.0) {
                        bwd += d[q];
                        nb++;
                    }
                }
                double f = nf > 0 ? fwd / nf : -1;
                double g = nb > 0 ? bwd / nb : -1;
                if (f < g) {
                    nx = -nx;
                    ny = -ny;
                }
                // The way from barb to barb bends out toward the point too: both must agree. Of
                // several equally short ways, which one is found depends on the barb it starts
                // from, so the way is walked from each barb and the two are added up.
                double bulge = bulge(backTo[i], e2, e1, mx, my, nx, ny) + bulge(backTo[j], e1, e2, mx, my, nx, ny);
                if (Double.isNaN(bulge) || bulge <= 0) {
                    continue;
                }
                // A head is its own mirror image across its axis; a wrong pair is not.
                double ux = (x(e2) - x(e1)) / gap;
                double uy = (y(e2) - y(e1)) / gap;
                int near = 0;
                int hit = 0;
                for (int q = 0; q < G * G; q++) {
                    if (!body[q] || Math.hypot(x(q) - mx, y(q) - my) >= gap * 0.6) {
                        continue;
                    }
                    near++;
                    double t = (x(q) - mx) * ux + (y(q) - my) * uy;
                    int rx = (int) Math.rint(x(q) - 2 * t * ux);
                    int ry = (int) Math.rint(y(q) - 2 * t * uy);
                    if (rx >= 0 && ry >= 0 && rx < G && ry < G && body[ry * G + rx]) {
                        hit++;
                    }
                }
                double sym = near == 0 ? 0 : hit / (double) near;
                if (sym < 0.85) {
                    continue;
                }
                if (sym > bestSym) {
                    bestSym = sym;
                    bestE1 = e1;
                    bestE2 = e2;
                    bestNx = nx;
                    bestNy = ny;
                    bestMid = midY * G + midX;
                }
            }
        }
        int none = holes > 0 ? ARRIVE : UNKNOWN;
        if (bestE1 < 0) {
            return none;
        }
        double angle = Math.toDegrees(Math.atan2(bestNx, -bestNy));
        double a = Math.abs(angle);
        boolean right = angle > 0;
        int kind;
        if (a < 22) {
            return STRAIGHT;
        } else if (a < 67) {
            kind = right ? SLIGHT_RIGHT : SLIGHT_LEFT;
        } else if (a < 115) {
            kind = right ? RIGHT : LEFT;
        } else if (a < 158) {
            kind = right ? SHARP_RIGHT : SHARP_LEFT;
        } else {
            return x(bestMid) < x(low) ? BACK_LEFT : BACK_RIGHT;
        }
        // A turn's head lies on its own side of where the stem comes in.
        if ((x(bestMid) - x(low)) * (right ? 1 : -1) < -width) {
            return none;
        }
        return kind;
    }

    /**
     * How far the way from one cell back to another stands out along (nx, ny) from the
     * point (mx, my), summed over its cells; NaN when the way does not arrive.
     */
    private static double bulge(int[] back, int from, int to, double mx, double my, double nx, double ny) {
        double sum = 0;
        int at = from;
        for (int guard = 0; guard <= G * G; guard++) {
            sum += (x(at) - mx) * nx + (y(at) - my) * ny;
            if (at == to) {
                return sum;
            }
            at = back[at];
            if (at < 0) {
                break;
            }
        }
        return Double.NaN;
    }

    private static int x(int p) {
        return p % G;
    }

    private static int y(int p) {
        return p / G;
    }

    private static boolean in(int x, int y) {
        return x >= 0 && y >= 0 && x < G && y < G;
    }

    private static boolean onEdge(boolean[] body, int p) {
        for (int[] n : N8) {
            int qx = x(p) + n[0];
            int qy = y(p) + n[1];
            if (!in(qx, qy) || !body[qy * G + qx]) {
                return true;
            }
        }
        return false;
    }

    private static boolean nearBody(boolean[] body, int cx, int cy) {
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (in(cx + dx, cy + dy) && body[(cy + dy) * G + cx + dx]) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The largest 8-connected piece of the mask; dashes under a "straight" arrow fall away. */
    private static boolean[] largestPiece(boolean[] mask) {
        int[] label = new int[G * G];
        int[] stack = new int[G * G];
        int bestLabel = 0;
        int bestSize = 0;
        int next = 0;
        for (int s = 0; s < G * G; s++) {
            if (!mask[s] || label[s] != 0) {
                continue;
            }
            next++;
            int top = 0;
            stack[top++] = s;
            label[s] = next;
            int size = 0;
            while (top > 0) {
                int p = stack[--top];
                size++;
                for (int[] n : N8) {
                    int qx = x(p) + n[0];
                    int qy = y(p) + n[1];
                    if (in(qx, qy)) {
                        int q = qy * G + qx;
                        if (mask[q] && label[q] == 0) {
                            label[q] = next;
                            stack[top++] = q;
                        }
                    }
                }
            }
            if (size > bestSize) {
                bestSize = size;
                bestLabel = next;
            }
        }
        boolean[] body = new boolean[G * G];
        for (int p = 0; p < G * G; p++) {
            body[p] = bestLabel != 0 && label[p] == bestLabel;
        }
        return body;
    }

    /** Closed gaps in the shape (4-connected, not touching the border, three cells or more). */
    private static int holes(boolean[] body) {
        boolean[] seen = new boolean[G * G];
        int[] stack = new int[G * G];
        int count = 0;
        int[][] n4 = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int s = 0; s < G * G; s++) {
            if (body[s] || seen[s]) {
                continue;
            }
            int top = 0;
            stack[top++] = s;
            seen[s] = true;
            int size = 0;
            boolean border = false;
            while (top > 0) {
                int p = stack[--top];
                size++;
                if (x(p) == 0 || y(p) == 0 || x(p) == G - 1 || y(p) == G - 1) {
                    border = true;
                }
                for (int[] n : n4) {
                    int qx = x(p) + n[0];
                    int qy = y(p) + n[1];
                    if (in(qx, qy)) {
                        int q = qy * G + qx;
                        if (!body[q] && !seen[q]) {
                            seen[q] = true;
                            stack[top++] = q;
                        }
                    }
                }
            }
            if (!border && size >= 3) {
                count++;
            }
        }
        return count;
    }

    /**
     * Distances along the shape from the given cells (chamfer 2/3), FAR where not reached.
     * When back is given, it receives for each cell the one it was reached from.
     */
    private static int[] along(boolean[] body, List<Integer> from, int[] back) {
        final int[] d = new int[G * G];
        Arrays.fill(d, FAR);
        if (back != null) {
            Arrays.fill(back, -1);
        }
        // Ordered by distance, then by column, then by row: ties settle the same way every time.
        PriorityQueue<long[]> heap = new PriorityQueue<long[]>(64, new java.util.Comparator<long[]>() {
            @Override
            public int compare(long[] a, long[] b) {
                if (a[0] != b[0]) {
                    return a[0] < b[0] ? -1 : 1;
                }
                if (a[1] != b[1]) {
                    return a[1] < b[1] ? -1 : 1;
                }
                return Long.compare(a[2], b[2]);
            }
        });
        for (int s : from) {
            d[s] = 0;
            heap.add(new long[] {0, x(s), y(s)});
        }
        while (!heap.isEmpty()) {
            long[] top = heap.poll();
            int k = (int) top[0];
            int p = (int) top[2] * G + (int) top[1];
            if (k > d[p]) {
                continue;
            }
            for (int[] n : N8) {
                int qx = x(p) + n[0];
                int qy = y(p) + n[1];
                if (!in(qx, qy)) {
                    continue;
                }
                int q = qy * G + qx;
                if (body[q] && k + n[2] < d[q]) {
                    d[q] = k + n[2];
                    if (back != null) {
                        back[q] = p;
                    }
                    heap.add(new long[] {k + n[2], qx, qy});
                }
            }
        }
        return d;
    }

    /** No cell within r lies farther along; on a tie, the larger cell in (column, row) order wins. */
    private static boolean isEnd(int[] d, int p, int r) {
        int v = d[p];
        for (int dy = -r; dy <= r; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                int qx = x(p) + dx;
                int qy = y(p) + dy;
                if (!in(qx, qy)) {
                    continue;
                }
                int dq = d[qy * G + qx];
                if (dq == FAR) {
                    continue;
                }
                if (dq > v || dq == v && (qx > x(p) || qx == x(p) && qy > y(p))) {
                    return false;
                }
            }
        }
        return true;
    }
}
