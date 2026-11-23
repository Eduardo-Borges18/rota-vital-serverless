package br.edu.cesar.rotavital;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

public final class Demonstracao {
    private static final int PORTA_HTTP = 8080;
    private static final int PORTA_EVENTO = 8081;
    private static final String TIPO_JSON = "application/json";
    private static final String TIPO_EVENTO = "application/cloudevents+json";
    private static final String CURL = System.getProperty("os.name").startsWith("Windows") ? "curl.exe" : "curl";
    private final boolean gravacao;

    private Demonstracao(boolean gravacao) {
        this.gravacao = gravacao;
    }

    public static void main(String[] argumentos) throws Exception {
        new Demonstracao(Arrays.asList(argumentos).contains("--gravacao")).executar();
    }

    private void executar() throws Exception {
        secao("ROTA VITAL | ATIVIDADE INDIVIDUAL DE SERVERLESS");
        System.out.println("Java " + Runtime.version().feature() + " em execução · compilação para Java 21");
        System.out.println("Invocador local oficial do Google Cloud Functions Framework");
        System.out.println("CloudEvent · POST /guias · Reaproveitamento da JVM · Timeout");
        System.out.println("> mvn -q test-compile exec:java");
        pausar(8);

        secao("01 | INVOCAÇÃO POR CLOUD EVENT");
        System.out.println("> " + InvocadorLocal.comando("GuiaEvento", PORTA_EVENTO));
        try (var processo = new InvocadorLocal("GuiaEvento", PORTA_EVENTO, 3)) {
            System.out.println("Inicialização da JVM e do framework: " + InvocadorLocal.formatarMs(processo.inicializacaoMs()));
            for (int tentativa = 1; tentativa <= 2; tentativa++) {
                System.out.println("\nCloudEvent " + tentativa);
                post(PORTA_EVENTO, "/", TIPO_EVENTO, "pedido.json", 200);
                String log = processo.aguardarLog("FaaS CloudEvent ");
                System.out.println("Log da função: " + log.substring("FaaS CloudEvent ".length()));
            }
        }
        pausar(18);

        secao("02 | POST /guias · UM PROCESSO NOVO POR CHAMADA");
        System.out.println("> " + InvocadorLocal.comando("GuiaHttp", PORTA_HTTP));
        for (int tentativa = 1; tentativa <= 3; tentativa++) {
            System.out.println("\nPOST " + tentativa + " · JVM nova");
            try (var processo = new InvocadorLocal("GuiaHttp", PORTA_HTTP, 3)) {
                verificarContador(post(PORTA_HTTP, "/guias", TIPO_JSON, "pedido-http.json", 201), 1);
            }
        }
        pausar(14);

        secao("03 | POST /guias · TRÊS CHAMADAS NA MESMA JVM");
        System.out.println("> " + InvocadorLocal.comando("GuiaHttp", PORTA_HTTP));
        try (var processo = new InvocadorLocal("GuiaHttp", PORTA_HTTP, 3)) {
            for (int tentativa = 1; tentativa <= 3; tentativa++) {
                System.out.println("\nPOST " + tentativa + " · mesma JVM");
                verificarContador(post(PORTA_HTTP, "/guias", TIPO_JSON, "pedido-http.json", 201), tentativa);
            }
        }
        pausar(15);

        secao("04 | TEMPO LIMITE · ESPERA DE 5 SEGUNDOS");
        System.out.println("LIMITE_SEGUNDOS=3");
        System.out.println("> " + InvocadorLocal.comando("GuiaHttp", PORTA_HTTP));
        try (var processo = new InvocadorLocal("GuiaHttp", PORTA_HTTP, 3)) {
            post(PORTA_HTTP, "/guias", TIPO_JSON, "pedido-lento.json", 504);
            System.out.println(processo.aguardarLog("FaaS timeout: "));
        }
        pausar(12);

        System.out.println("\nMesmo pedido, após reiniciar com LIMITE_SEGUNDOS=10");
        System.out.println("> " + InvocadorLocal.comando("GuiaHttp", PORTA_HTTP));
        try (var processo = new InvocadorLocal("GuiaHttp", PORTA_HTTP, 10)) {
            post(PORTA_HTTP, "/guias", TIPO_JSON, "pedido-lento.json", 201);
        }
        System.out.println("\nDemonstração concluída.");
        pausar(15);
    }

    private static JsonObject post(int porta, String rota, String tipo, String arquivo, int esperado) throws Exception {
        String url = "http://127.0.0.1:" + porta + rota;
        String corpo = "@events/" + arquivo;
        String formato = "\\nHTTP %{http_code} | %{time_total} s\\n";
        System.out.println("> %s -sS -H \"Content-Type: %s\" --data-binary %s %s".formatted(CURL, tipo, corpo, url));
        var processo = new ProcessBuilder(List.of(CURL, "-sS", "--max-time", "15", "-H", "Content-Type: " + tipo,
                "--data-binary", corpo, "-w", formato, url)).redirectErrorStream(true).start();
        String saida = new String(processo.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        System.out.println(saida);
        String marcador = "HTTP " + esperado + " |";
        if (processo.waitFor() != 0 || !saida.contains(marcador)) {
            throw new IOException("Esperado " + marcador + " para " + arquivo);
        }
        return esperado == 201
                ? JsonParser.parseString(saida.substring(0, saida.lastIndexOf(marcador)).strip()).getAsJsonObject()
                : null;
    }

    private static void verificarContador(JsonObject guia, int esperado) {
        if (!"G-R-002".equals(guia.get("guia").getAsString()) || guia.get("invocacaoNesteAmbiente").getAsInt() != esperado) {
            throw new IllegalStateException("Guia ou contador diferente do esperado: " + guia);
        }
    }

    private void pausar(int segundos) throws InterruptedException {
        if (gravacao) Thread.sleep(segundos * 1_000L);
    }

    private static void secao(String titulo) {
        System.out.println("\n" + titulo + "\n");
    }
}
