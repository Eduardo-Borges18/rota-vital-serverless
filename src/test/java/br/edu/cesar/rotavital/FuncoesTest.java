package br.edu.cesar.rotavital;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class FuncoesTest {
    private final HttpClient cliente = HttpClient.newHttpClient();

    @Test
    void geraGuiaHttpEReaproveitaContador() throws Exception {
        int porta = portaLivre();
        try (var servidor = new InvocadorLocal("GuiaHttp", porta, 3)) {
            String pedido = """
                    {"requisicaoId":"R-002","hospital":"H-Caruaru"}
                    """;
            var primeira = post(porta, "/guias", "application/json", pedido);
            assertEquals(201, primeira.statusCode());
            assertEquals("""
                    {"guia":"G-R-002","hospital":"H-Caruaru","bolsas":[],"invocacaoNesteAmbiente":1}""",
                    primeira.body());
            var segunda = post(porta, "/guias", "application/json", pedido);
            assertTrue(segunda.body().contains("\"invocacaoNesteAmbiente\":2"));
        }
        int novaPorta = portaLivre();
        try (var outro = new InvocadorLocal("GuiaHttp", novaPorta, 3)) {
            var resposta = post(novaPorta, "/guias", "application/json", "{\"requisicaoId\":\"R-002\"}");
            assertTrue(resposta.body().contains("\"invocacaoNesteAmbiente\":1"));
        }
    }

    @Test
    void rejeitaEntradaInvalida() throws Exception {
        int porta = portaLivre();
        try (var servidor = new InvocadorLocal("GuiaHttp", porta, 3)) {
            assertEquals(400, post(porta, "/guias", "application/json", "{}").statusCode());
            assertEquals(400, post(porta, "/guias", "application/json", "{invalid}").statusCode());
            assertEquals(400, post(porta, "/guias", "application/json",
                    "{\"requisicaoId\":\"R-1\",\"bolsas\":\"O-NEG\"}").statusCode());
            assertEquals(415, post(porta, "/guias", "text/plain", "{}").statusCode());
            assertEquals(404, post(porta, "/outra", "application/json", "{}").statusCode());
            assertEquals(413, post(porta, "/guias", "application/json", " ".repeat(17000)).statusCode());
            var get = HttpRequest.newBuilder(uri(porta, "/guias")).GET().build();
            var response = cliente.send(get, HttpResponse.BodyHandlers.ofString());
            assertEquals(405, response.statusCode());
            assertEquals("POST", response.headers().firstValue("Allow").orElseThrow());
        }
    }

    @Test
    void processaCloudEventNoFramework() throws Exception {
        int porta = portaLivre();
        try (var servidor = new InvocadorLocal("GuiaEvento", porta, 3)) {
            String evento = Files.readString(Path.of("events/pedido.json"));
            var resposta = post(porta, "/", "application/cloudevents+json", evento);
            assertEquals(200, resposta.statusCode());
            assertTrue(servidor.aguardarLog("FaaS CloudEvent ").contains("\"guia\":\"G-R-001\""));
        }
    }

    @Test
    void esperaCincoSegundosComLimitesDeTresEDez() throws Exception {
        int portaCurta = portaLivre();
        String lento = Files.readString(Path.of("events/pedido-lento.json"));
        try (var curto = new InvocadorLocal("GuiaHttp", portaCurta, 3)) {
            long inicio = System.nanoTime();
            var erro = post(portaCurta, "/guias", "application/json", lento);
            long ms = Duration.ofNanos(System.nanoTime() - inicio).toMillis();
            assertEquals(504, erro.statusCode());
            assertTrue(ms >= 2900 && ms < 4900, "O limite deve interromper a espera: " + ms);
            assertTrue(erro.body().contains("Tempo limite de execução excedido"));
        }
        int portaLonga = portaLivre();
        try (var longo = new InvocadorLocal("GuiaHttp", portaLonga, 10)) {
            var guia = post(portaLonga, "/guias", "application/json", lento);
            assertEquals(201, guia.statusCode());
            assertTrue(guia.body().contains("\"guia\":\"G-R-003\""));
        }
    }

    private HttpResponse<String> post(int porta, String caminho, String tipo, String corpo) throws Exception {
        var pedido = HttpRequest.newBuilder(uri(porta, caminho)).timeout(Duration.ofSeconds(15))
                .header("Content-Type", tipo).POST(HttpRequest.BodyPublishers.ofString(corpo)).build();
        return cliente.send(pedido, HttpResponse.BodyHandlers.ofString());
    }

    private static URI uri(int porta, String caminho) {
        return URI.create("http://127.0.0.1:" + porta + caminho);
    }

    private static int portaLivre() throws Exception {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
