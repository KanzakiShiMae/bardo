package com.musicplayer.services;

import be.tarsos.dsp.AudioDispatcher;
import be.tarsos.dsp.AudioEvent;
import be.tarsos.dsp.AudioProcessor;
import be.tarsos.dsp.io.jvm.AudioDispatcherFactory;
import be.tarsos.dsp.util.fft.FFT;
import be.tarsos.dsp.util.fft.HammingWindow;
import com.musicplayer.models.Song;
import ws.schild.jave.Encoder;
import ws.schild.jave.MultimediaObject;
import ws.schild.jave.encode.AudioAttributes;
import ws.schild.jave.encode.EncodingAttributes;

import javax.sound.sampled.AudioSystem;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Gestiona el cómputo, almacenamiento y carga de espectrogramas usando TarsosDSP.
 *
 * <p>El flujo de generación es:
 * <ol>
 *   <li>Convertir el archivo de audio (M4A/MP4) a WAV mono 22 kHz usando jave.</li>
 *   <li>Procesar el WAV con {@code AudioDispatcher} + {@code FFT} de TarsosDSP.</li>
 *   <li>Mapear las amplitudes a {@value #N_BANDS} bandas logarítmicas por frame.</li>
 *   <li>Guardar el resultado como {@code {songId}.spg} en disco.</li>
 * </ol>
 *
 * <p>El progreso (0–100 %) es consultable en tiempo real mediante {@link #getProgress}.
 *
 * <p>Caché en disco: {@code %APPDATA%\Bardo\spectrograms\{songId}.spg}.
 * Formato: cabecera {@code "SPGM"} + {@code numSlices} (int) + {@code numBands} (int)
 * + datos row-major en bytes.
 */
public class SpectrogramService {

    public static final int N_BANDS = 32;

    private static final byte[] MAGIC       = {'S', 'P', 'G', 'M'};
    private static final int    SAMPLE_RATE = 22050;
    private static final int    BUFFER_SIZE = 4096;
    private static final int    OVERLAP     = BUFFER_SIZE / 2;

    private final Path cacheDir;
    private final ExecutorService executor;

    /** songId → progreso 0–1000 (null cuando no se está generando). */
    private final Map<String, AtomicInteger> progressMap = new ConcurrentHashMap<>();

    // ── Constructor ───────────────────────────────────────────────────────────

    public SpectrogramService() {
        String appData = System.getenv("APPDATA");
        Path base = appData != null
            ? Path.of(appData, "Bardo")
            : Path.of(System.getProperty("user.home"), ".bardo");
        cacheDir = base.resolve("spectrograms");
        try { Files.createDirectories(cacheDir); } catch (IOException ignored) {}

        executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "bardo-spectrogram");
            t.setDaemon(true);
            return t;
        });
    }

    // ── Song ID ───────────────────────────────────────────────────────────────

    public String getSongId(Song song) {
        if (song == null) return null;
        String id = song.getVideoId();
        if (id == null || id.isBlank()) return null;
        if (id.length() == 11 && id.matches("[A-Za-z0-9_\\-]+")) return id;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(id.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.substring(0, 32);
        } catch (Exception e) {
            return Integer.toHexString(id.hashCode());
        }
    }

    // ── Cache ─────────────────────────────────────────────────────────────────

    public boolean hasCached(String songId) {
        return songId != null && Files.exists(cacheDir.resolve(songId + ".spg"));
    }

    public boolean isGenerating(String songId) {
        return songId != null && progressMap.containsKey(songId);
    }

    /**
     * Retorna el progreso de generación en [0.0, 1.0], o -1 si no está en curso.
     */
    public float getProgress(String songId) {
        if (songId == null) return -1f;
        AtomicInteger p = progressMap.get(songId);
        return p != null ? p.get() / 1000f : -1f;
    }

    public byte[][] load(String songId) {
        if (songId == null) return null;
        Path file = cacheDir.resolve(songId + ".spg");
        if (!Files.exists(file)) return null;
        try (DataInputStream dis = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(file)))) {
            byte[] magic = new byte[4];
            dis.readFully(magic);
            if (!Arrays.equals(magic, MAGIC)) return null;
            int numSlices = dis.readInt();
            int numBands  = dis.readInt();
            if (numSlices <= 0 || numBands <= 0 || numSlices > 100_000) return null;
            byte[][] data = new byte[numSlices][numBands];
            for (int i = 0; i < numSlices; i++) dis.readFully(data[i]);
            return data;
        } catch (IOException e) {
            return null;
        }
    }

    private void save(String songId, List<byte[]> slices) {
        if (songId == null || slices.isEmpty()) return;
        Path file = cacheDir.resolve(songId + ".spg");
        try (DataOutputStream dos = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(file)))) {
            dos.write(MAGIC);
            dos.writeInt(slices.size());
            dos.writeInt(slices.get(0).length);
            for (byte[] slice : slices) dos.write(slice);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // ── Cómputo con TarsosDSP ─────────────────────────────────────────────────

    /**
     * Inicia el cómputo del espectrograma en background.
     * Si ya hay caché o ya se está generando, no hace nada y retorna un future
     * completado.
     *
     * @param songId    Identificador de la canción (ver {@link #getSongId})
     * @param audioFile Ruta al archivo de audio descargado (M4A, MP4, etc.)
     */
    public CompletableFuture<Void> computeFromFile(String songId, Path audioFile) {
        if (songId == null || hasCached(songId) || isGenerating(songId))
            return CompletableFuture.completedFuture(null);

        AtomicInteger progress = new AtomicInteger(0);
        progressMap.put(songId, progress);

        return CompletableFuture.runAsync(() -> {
            Path wavFile = null;
            try {
                wavFile = convertToWav(audioFile);

                long frameLength = AudioSystem.getAudioFileFormat(wavFile.toFile()).getFrameLength();
                double totalDuration = frameLength / (double) SAMPLE_RATE;

                // Pre-computar límites de bandas logarítmicas una sola vez
                final float fMin        = 20f;
                final float fMax        = SAMPLE_RATE / 2f;
                final float freqPerBin  = (float) SAMPLE_RATE / BUFFER_SIZE;
                final int[] binLow      = new int[N_BANDS];
                final int[] binHigh     = new int[N_BANDS];
                for (int band = 0; band < N_BANDS; band++) {
                    binLow[band]  = Math.max(1, (int)(
                        fMin * Math.pow(fMax / fMin, (double) band / N_BANDS) / freqPerBin));
                    binHigh[band] = Math.min(BUFFER_SIZE / 2 - 1, (int)(
                        fMin * Math.pow(fMax / fMin, (double)(band + 1) / N_BANDS) / freqPerBin));
                    if (binHigh[band] < binLow[band]) binHigh[band] = binLow[band];
                }

                List<byte[]> slices = new ArrayList<>();
                FFT fft = new FFT(BUFFER_SIZE, new HammingWindow());
                float[] amplitudes = new float[BUFFER_SIZE / 2];

                AudioDispatcher dispatcher =
                    AudioDispatcherFactory.fromFile(wavFile.toFile(), BUFFER_SIZE, OVERLAP);

                dispatcher.addAudioProcessor(new AudioProcessor() {
                    @Override
                    public boolean process(AudioEvent audioEvent) {
                        float[] buffer = audioEvent.getFloatBuffer().clone();
                        fft.forwardTransform(buffer);
                        fft.modulus(buffer, amplitudes);

                        byte[] slice = new byte[N_BANDS];
                        for (int band = 0; band < N_BANDS; band++) {
                            float sum = 0;
                            for (int b = binLow[band]; b <= binHigh[band]; b++)
                                sum += amplitudes[b];
                            float avg = sum / (binHigh[band] - binLow[band] + 1);
                            float norm = avg / (BUFFER_SIZE / 2f);
                            float db  = norm > 1e-7f ? 20f * (float) Math.log10(norm) : -80f;
                            db = Math.max(-80f, Math.min(0f, db));
                            slice[band] = (byte)(int)((db + 80f) / 80f * 255f);
                        }
                        slices.add(slice);

                        if (totalDuration > 0)
                            progress.set(Math.min(999,
                                (int)(audioEvent.getEndTimeStamp() / totalDuration * 1000)));
                        return true;
                    }

                    @Override
                    public void processingFinished() {}
                });

                dispatcher.run();

                if (!slices.isEmpty()) save(songId, slices);

            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                progressMap.remove(songId);
                if (wavFile != null)
                    try { Files.deleteIfExists(wavFile); } catch (IOException ignored) {}
            }
        }, executor);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Path convertToWav(Path audioFile) throws Exception {
        Path wavFile = Files.createTempFile("bardo_spg_", ".wav");
        AudioAttributes audio = new AudioAttributes();
        audio.setCodec("pcm_s16le");
        audio.setChannels(1);
        audio.setSamplingRate(SAMPLE_RATE);
        EncodingAttributes attrs = new EncodingAttributes();
        attrs.setOutputFormat("wav");
        attrs.setAudioAttributes(audio);
        new Encoder().encode(new MultimediaObject(audioFile.toFile()), wavFile.toFile(), attrs);
        return wavFile;
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
