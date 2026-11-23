package br.edu.cesar.rotavital;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.Strictness;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

record Pedido(String requisicaoId, String hospital, List<String> bolsas, boolean demorar) {
    static final int MAX_BYTES = 16 * 1024;
    static final Gson JSON = new GsonBuilder().setStrictness(Strictness.STRICT).serializeNulls().create();

    static Pedido ler(byte[] dados) {
        if (dados.length > MAX_BYTES) throw new IllegalArgumentException("O pedido excede 16 KiB");
        JsonObject objeto;
        try {
            objeto = JSON.fromJson(new String(dados, StandardCharsets.UTF_8), JsonObject.class);
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("O pedido precisa ser um objeto JSON válido", e);
        }
        if (objeto == null) throw new IllegalArgumentException("O pedido precisa ser um objeto JSON válido");

        String id = texto(objeto.get("requisicaoId"), "requisicaoId obrigatório").strip();
        JsonElement campoHospital = objeto.get("hospital");
        String hospital = campoHospital == null || campoHospital.isJsonNull() ? null
                : texto(campoHospital, "hospital deve ser um texto preenchido ou null");

        var bolsas = new ArrayList<String>();
        JsonElement campoBolsas = objeto.get("bolsas");
        if (campoBolsas != null) {
            String mensagem = "bolsas deve ser uma lista de códigos";
            if (!campoBolsas.isJsonArray()) throw new IllegalArgumentException(mensagem);
            for (JsonElement bolsa : campoBolsas.getAsJsonArray()) bolsas.add(texto(bolsa, mensagem));
        }

        JsonElement campoDemorar = objeto.get("demorar");
        if (campoDemorar != null && (!campoDemorar.isJsonPrimitive() || !campoDemorar.getAsJsonPrimitive().isBoolean())) {
            throw new IllegalArgumentException("demorar deve ser um booleano");
        }
        return new Pedido(id, hospital, List.copyOf(bolsas), campoDemorar != null && campoDemorar.getAsBoolean());
    }

    private static String texto(JsonElement campo, String mensagem) {
        if (campo == null || !campo.isJsonPrimitive() || !campo.getAsJsonPrimitive().isString()
                || campo.getAsString().isBlank()) {
            throw new IllegalArgumentException(mensagem);
        }
        return campo.getAsString();
    }
}
