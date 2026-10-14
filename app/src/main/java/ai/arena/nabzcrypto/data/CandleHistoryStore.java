package ai.arena.nabzcrypto.data;

import android.content.Context;

import ai.arena.nabzcrypto.model.Candle;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Incremental private 5-minute history: 10 days normally, up to 30 days for favorites. */
public final class CandleHistoryStore {
    private static final int VERSION = 1;
    private static final int NORMAL_MAX = 10 * 24 * 12;
    private static final int FAVORITE_MAX = 30 * 24 * 12;
    private final File directory;

    public CandleHistoryStore(Context context) {
        directory = new File(context.getFilesDir(), "candle_history_v1");
        if (!directory.exists()) directory.mkdirs();
    }

    public synchronized List<Candle> merge(String symbol, List<Candle> fresh, boolean favorite) {
        int maximum = favorite ? FAVORITE_MAX : NORMAL_MAX;
        List<Candle> result = mergeBounded(read(symbol), fresh, maximum);
        write(symbol, result);
        return result;
    }

    /** Pure merge primitive used by the persistent cache and deterministic unit tests. */
    static List<Candle> mergeBounded(List<Candle> cached, List<Candle> fresh, int maximum) {
        TreeMap<Long, Candle> merged = new TreeMap<>();
        if (cached != null) {
            for (Candle candle : cached) {
                if (candle != null && candle.isValid()) merged.put(candle.openTime, candle);
            }
        }
        if (fresh != null) {
            for (Candle candle : fresh) {
                if (candle != null && candle.isValid()) merged.put(candle.openTime, candle);
            }
        }
        int safeMaximum = Math.max(1, maximum);
        while (merged.size() > safeMaximum) merged.pollFirstEntry();
        return new ArrayList<>(merged.values());
    }

    public synchronized void clear() {
        File[] files = directory.listFiles();
        if (files != null) for (File file : files) file.delete();
    }

    private List<Candle> read(String symbol) {
        List<Candle> result = new ArrayList<>();
        File file = file(symbol);
        if (!file.isFile()) return result;
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(
                new GZIPInputStream(new FileInputStream(file))))) {
            if (input.readInt() != VERSION) return result;
            int count = input.readInt();
            if (count < 0 || count > FAVORITE_MAX) return result;
            for (int i = 0; i < count; i++) {
                long openTime = input.readLong();
                long closeTime = input.readLong();
                result.add(new Candle(openTime, closeTime,
                        input.readDouble(), input.readDouble(), input.readDouble(),
                        input.readDouble(), input.readDouble(), input.readDouble()));
            }
        } catch (EOFException ignored) {
            result.clear();
        } catch (IOException | RuntimeException ignored) {
            result.clear();
        }
        return result;
    }

    private void write(String symbol, List<Candle> candles) {
        File target = file(symbol);
        File temporary = new File(target.getPath() + ".tmp");
        try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(
                new GZIPOutputStream(new FileOutputStream(temporary))))) {
            output.writeInt(VERSION);
            output.writeInt(candles.size());
            for (Candle candle : candles) {
                output.writeLong(candle.openTime);
                output.writeLong(candle.closeTime);
                output.writeDouble(candle.open);
                output.writeDouble(candle.high);
                output.writeDouble(candle.low);
                output.writeDouble(candle.close);
                output.writeDouble(candle.baseVolume);
                output.writeDouble(candle.quoteVolume);
            }
        } catch (IOException | RuntimeException ignored) {
            temporary.delete();
            return;
        }
        if (!temporary.renameTo(target)) {
            target.delete();
            temporary.renameTo(target);
        }
    }

    private File file(String symbol) {
        String safe = symbol == null ? "UNKNOWN" : symbol.toUpperCase(Locale.US)
                .replaceAll("[^A-Z0-9]", "");
        return new File(directory, (safe.isEmpty() ? "UNKNOWN" : safe) + ".bin.gz");
    }
}
