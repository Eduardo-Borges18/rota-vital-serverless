package br.edu.cesar.rotavital;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

record Guia(String guia, String hospital, List<String> bolsas, long invocacaoNesteAmbiente) {
    private static final AtomicLong INVOCACOES = new AtomicLong();

    static Guia gerar(Pedido pedido) throws InterruptedException {
        long invocacao = INVOCACOES.incrementAndGet();
        if (pedido.demorar()) Thread.sleep(5_000);
        return new Guia("G-" + pedido.requisicaoId(), pedido.hospital(), pedido.bolsas(), invocacao);
    }
}
