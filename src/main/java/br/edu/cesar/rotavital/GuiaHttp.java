package br.edu.cesar.rotavital;

import com.google.cloud.functions.HttpFunction;
import com.google.cloud.functions.HttpRequest;
import com.google.cloud.functions.HttpResponse;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class GuiaHttp implements HttpFunction {
    private final long limiteSegundos = lerLimiteLocal();

    @Override
    public void service(HttpRequest requisicao, HttpResponse resposta) throws IOException {
        if (!"/guias".equals(requisicao.getPath())) {
            escrever(resposta, 404, Map.of("erro", "Rota não encontrada"));
            return;
        }
        if (!"POST".equals(requisicao.getMethod())) {
            resposta.appendHeader("Allow", "POST");
            escrever(resposta, 405, Map.of("erro", "Use POST /guias"));
            return;
        }
        String tipo = requisicao.getContentType().orElse("").split(";", 2)[0].trim();
        if (!"application/json".equalsIgnoreCase(tipo)) {
            escrever(resposta, 415, Map.of("erro", "Use Content-Type: application/json"));
            return;
        }

        byte[] corpo = requisicao.getInputStream().readNBytes(Pedido.MAX_BYTES + 1);
        if (corpo.length > Pedido.MAX_BYTES) {
            escrever(resposta, 413, Map.of("erro", "O pedido excede 16 KiB"));
            return;
        }
        Pedido pedido;
        try {
            pedido = Pedido.ler(corpo);
        } catch (IllegalArgumentException e) {
            escrever(resposta, 400, Map.of("erro", e.getMessage()));
            return;
        }

        var tarefa = new FutureTask<>(() -> Guia.gerar(pedido));
        Thread.startVirtualThread(tarefa);
        try {
            escrever(resposta, 201, tarefa.get(limiteSegundos, TimeUnit.SECONDS));
        } catch (TimeoutException e) {
            tarefa.cancel(true);
            System.out.println("FaaS timeout: geração interrompida pelo limite local de " + limiteSegundos + " s");
            escrever(resposta, 504, Map.of("erro", "Tempo limite de execução excedido", "limiteSegundos", limiteSegundos));
        } catch (InterruptedException e) {
            tarefa.cancel(true);
            Thread.currentThread().interrupt();
            throw new IOException("A invocação HTTP foi interrompida", e);
        } catch (ExecutionException e) {
            throw new IOException("Falha ao gerar a guia", e.getCause());
        }
    }

    private static void escrever(HttpResponse resposta, int status, Object corpo) throws IOException {
        resposta.setStatusCode(status);
        resposta.setContentType("application/json; charset=utf-8");
        resposta.getWriter().write(Pedido.JSON.toJson(corpo));
    }

    private static long lerLimiteLocal() {
        String configuracao = System.getenv().getOrDefault("LIMITE_SEGUNDOS", "3");
        long segundos = Long.parseLong(configuracao);
        if (segundos <= 0) throw new IllegalArgumentException("LIMITE_SEGUNDOS deve ser um inteiro positivo");
        return segundos;
    }
}
