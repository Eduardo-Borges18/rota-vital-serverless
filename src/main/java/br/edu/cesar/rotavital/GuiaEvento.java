package br.edu.cesar.rotavital;

import com.google.cloud.functions.CloudEventsFunction;
import io.cloudevents.CloudEvent;

public final class GuiaEvento implements CloudEventsFunction {
    @Override
    public void accept(CloudEvent evento) throws InterruptedException {
        if (evento.getData() == null) throw new IllegalArgumentException("O CloudEvent precisa conter data");
        var pedido = Pedido.ler(evento.getData().toBytes());
        var guia = Pedido.JSON.toJsonTree(Guia.gerar(pedido)).getAsJsonObject();
        guia.addProperty("evento", evento.getId());
        System.out.println("FaaS CloudEvent " + guia);
    }
}
