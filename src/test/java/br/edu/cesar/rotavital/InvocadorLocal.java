package br.edu.cesar.rotavital;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

final class InvocadorLocal implements AutoCloseable {
    private static final String PREFIXO = "br.edu.cesar.rotavital.";
    private static final String CLASSPATH = "target/classes" + File.pathSeparator + "target/local/gson.jar";
    private static final boolean WINDOWS = System.getProperty("os.name").startsWith("Windows");
    private final Process processo;
    private final BlockingQueue<String> logs = new LinkedBlockingQueue<>();
    private final double inicializacaoMs;

    InvocadorLocal(String alvo, int porta, int limite) throws IOException, InterruptedException {
        var java = Path.of(System.getProperty("java.home"), "bin", WINDOWS ? "java.exe" : "java");
        var comando = new ProcessBuilder(java.toString(), "-Dstdout.encoding=UTF-8", "-jar", "target/local/invoker.jar",
                "--classpath", CLASSPATH, "--target", PREFIXO + alvo, "--port", Integer.toString(porta));
        comando.environment().put("LIMITE_SEGUNDOS", Integer.toString(limite));
        comando.redirectErrorStream(true);
        long inicio = System.nanoTime();
        processo = comando.start();
        Thread.ofVirtual().start(() -> processo.inputReader(StandardCharsets.UTF_8).lines().forEach(logs::add));
        try {
            aguardarPorta(porta);
        } catch (IOException | InterruptedException e) {
            close();
            throw e;
        }
        inicializacaoMs = (System.nanoTime() - inicio) / 1_000_000.0;
    }

    private void aguardarPorta(int porta) throws IOException, InterruptedException {
        long prazo = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (processo.isAlive() && System.nanoTime() < prazo) {
            try (var socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", porta), 250);
                return;
            } catch (IOException e) {
                Thread.sleep(35);
            }
        }
        throw new IOException("O Functions Framework não iniciou em " + porta + ": " + logs);
    }

    String aguardarLog(String prefixo) throws IOException, InterruptedException {
        long prazo = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        long restante;
        while ((restante = prazo - System.nanoTime()) > 0) {
            String linha = logs.poll(restante, TimeUnit.NANOSECONDS);
            if (linha == null) break;
            if (linha.startsWith(prefixo)) return linha;
        }
        throw new IOException("Log esperado não apareceu: " + prefixo);
    }

    static String comando(String alvo, int porta) {
        return "java -jar target/local/invoker.jar --classpath \"%s\" --target %s%s --port %d"
                .formatted(CLASSPATH, PREFIXO, alvo, porta);
    }

    static String formatarMs(double ms) {
        return String.format(Locale.ROOT, "%.1f ms", ms);
    }

    double inicializacaoMs() {
        return inicializacaoMs;
    }

    @Override
    public void close() {
        processo.destroy();
        try {
            if (!processo.waitFor(3, TimeUnit.SECONDS)) processo.destroyForcibly().waitFor();
        } catch (InterruptedException e) {
            processo.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }
}
