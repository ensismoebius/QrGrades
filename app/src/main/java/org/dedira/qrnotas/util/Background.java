/*
 * QrGrades — track student grades/points, scan QR codes to award points, and optionally
 * expose the same data to a browser on the local network.
 * Copyright (C) 2026 André Furlan
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.dedira.qrnotas.util;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Runs a blocking job off the main thread and hands its result back on the main thread. The
 * screens that need several database reads at once (a student's whole report, the week's alerts)
 * use the blocking {@link DatabaseSync}-based loaders through this, instead of nesting a dozen
 * callbacks of the asynchronous {@link Database} API.
 */
public final class Background {

    /** Work to run on a worker thread. */
    public interface Job<T> {
        T run() throws Exception;
    }

    /** Delivered on the main thread; exactly one of {@code result} / {@code error} is meaningful. */
    public interface Callback<T> {
        void onDone(T result, Exception error);
    }

    private static final ExecutorService POOL = Executors.newCachedThreadPool(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "qrgrades-background");
            t.setDaemon(true);
            return t;
        }
    });

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Background() {
    }

    public static <T> void run(final Job<T> job, final Callback<T> callback) {
        POOL.execute(new Runnable() {
            @Override
            public void run() {
                T value = null;
                Exception failure = null;
                try {
                    value = job.run();
                } catch (Exception e) {
                    failure = e;
                }
                final T result = value;
                final Exception error = failure;
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onDone(result, error);
                    }
                });
            }
        });
    }
}
