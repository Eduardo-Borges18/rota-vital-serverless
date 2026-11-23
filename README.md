# Rota Vital · Guia de transporte serverless

Atividade individual de Infraestrutura de Software, CESAR School, Unidade II (2026.2).

Escolhi rodar a atividade localmente com o **Google Cloud Functions Framework para Java**, uma das alternativas do enunciado. Usei pedidos fictícios do Rota Vital para simular a emissão de uma guia de transporte.

O [`GuiaHttp`](src/main/java/br/edu/cesar/rotavital/GuiaHttp.java) recebe o pedido em `POST /guias` e devolve `201` com a guia em JSON. O [`GuiaEvento`](src/main/java/br/edu/cesar/rotavital/GuiaEvento.java) recebe os dados do pedido em um CloudEvent e imprime a guia no log. Os dois chamam a mesma lógica de geração em [`Guia`](src/main/java/br/edu/cesar/rotavital/Guia.java).

## Como rodar

Você precisa de JDK 21 ou superior, Maven e `curl`. O projeto compila para Java 21. Na execução das evidências, foram usados OpenJDK 25.0.2, Maven 3.9.16 e o invocador Java 2.0.2. [Instalação do Maven](https://maven.apache.org/install.html)

```powershell
java -version
mvn -version
mvn clean verify
mvn -q test-compile exec:java
```

O `mvn clean verify` compila o projeto, roda os quatro testes e gera o pacote. O último comando executa a demonstração inteira com `curl`. Deixe as portas `8080` e `8081` livres. Para rodar com pausas entre as etapas, adicione `'-Dexec.args=--gravacao'` ao último comando.

### Como repetir cada etapa

Os exemplos estão em PowerShell. Antes de começar, rode `mvn test-compile` para preparar as classes e os JARs. No Linux ou macOS, use `curl`, separe o classpath com `:` e defina o prazo com `export LIMITE_SEGUNDOS=3` ou `export LIMITE_SEGUNDOS=10`.

#### 1. Invocar por evento

Inicie o invocador em um terminal.

```powershell
java -jar target/local/invoker.jar --classpath 'target/classes;target/local/gson.jar' --target br.edu.cesar.rotavital.GuiaEvento --port 8081
```

Em outro terminal, rode o comando abaixo duas vezes. A resposta é `200`, e a guia `G-R-001` aparece no log do primeiro terminal. O `time_total` mostra quanto durou cada chamada no cliente. Pare o invocador com `Ctrl+C` ao terminar.

```powershell
curl.exe -sS -w '\nHTTP %{http_code} | %{time_total} s\n' -H 'Content-Type: application/cloudevents+json' --data-binary '@events/pedido.json' http://127.0.0.1:8081/
```

#### 2. Acionar por HTTP e observar o contador

No primeiro terminal, inicie a função HTTP com limite de três segundos.

```powershell
$env:LIMITE_SEGUNDOS = '3'
java -jar target/local/invoker.jar --classpath 'target/classes;target/local/gson.jar' --target br.edu.cesar.rotavital.GuiaHttp --port 8080
```

No segundo terminal, faça três chamadas, com um processo Java novo para cada uma. Depois, inicie outro processo e faça as três chamadas nele, sem reiniciar. Compare `invocacaoNesteAmbiente` nas respostas.

```powershell
curl.exe -i -H 'Content-Type: application/json' --data-binary '@events/pedido-http.json' http://127.0.0.1:8080/guias
```

```json
{"guia":"G-R-002","hospital":"H-Caruaru","bolsas":[],"invocacaoNesteAmbiente":1}
```

O pedido precisa de `requisicaoId`. Se faltarem `hospital` e `bolsas`, a resposta usa `null` e `[]`. Uma entrada inválida retorna `400`.

#### 3. Testar o timeout

Com o limite de três segundos, o pedido abaixo retorna `504`. Depois, pare o invocador, configure `$env:LIMITE_SEGUNDOS = '10'` e inicie a função HTTP novamente. Repetindo o mesmo `curl`, a espera de cinco segundos termina e a resposta passa a ser `201`.

```powershell
curl.exe -sS -w '\nHTTP %{http_code} | %{time_total} s\n' -H 'Content-Type: application/json' --data-binary '@events/pedido-lento.json' http://127.0.0.1:8080/guias
```

No teste local, `GuiaHttp` usa `FutureTask.get` para esperar pelo resultado e `cancel(true)` para interromper a tarefa quando o tempo acaba. Numa implantação na nuvem, o limite da plataforma é configurado no próprio serviço.

## Resultados observados

| Experimento | Resultado |
| --- | --- |
| Inicialização da JVM e do framework até aceitar conexões | 429,0 ms |
| CloudEvents 1 e 2 no mesmo processo | `200` em 227,329 ms e 3,017 ms. O contador foi de `1` a `2` no log. |
| Três POSTs com reinício antes de cada chamada | `201` nas três chamadas. Contador `1`, `1`, `1`. |
| Três POSTs no mesmo processo | `201` nas três chamadas. Contador `1`, `2`, `3`. |
| Espera de 5 s, limite de 3 s | `504` em 3,128 s. |
| Mesma espera, limite de 10 s | `201` em 5,135 s, com a guia `G-R-003`. |

Os tempos dessa execução e as capturas estão na pasta `evidencias`. [Registro completo](evidencias/execucao.txt) · [Capturas dos experimentos](evidencias/)

## Perguntas do enunciado

### 1. Por que a primeira invocação demorou mais? O que acontece no Init?

A segunda chamada pegou o mesmo processo já em execução. A primeira ainda pode gastar tempo carregando classes e inicializando partes do framework. Como o `curl` só começa a medir depois que o servidor está pronto, o tempo de inicialização da JVM aparece separado na tabela. No Lambda, a fase Init prepara o runtime, inicia as extensões e executa o código que fica fora do handler, como imports e inicialização de variáveis. [Ciclo de vida do Lambda](https://docs.aws.amazon.com/lambda/latest/dg/lambda-runtime-environment.html)

### 2. O que o contador mostrou sobre guardar estado global?

Com uma JVM nova a cada pedido, o contador ficou em `1, 1, 1`. Com a mesma JVM, ficou em `1, 2, 3`. Esse foi o equivalente local ao reaproveitamento com `--warm-containers` no SAM. A variável estática pertence àquela instância e começa do zero em uma nova. Por isso, eu guardaria as guias e os dados de estoque em armazenamento persistente.

### 3. O que ocorreu com `demorar: true`? Qual é o tempo máximo no Lambda?

O pedido espera cinco segundos de propósito. Com três segundos de limite, a espera foi interrompida e a resposta foi `504`. Com dez segundos de limite, a guia saiu com `201`. Na AWS, o limite das Lambda Event Functions sob demanda passou de 15 para **30 minutos (1.800 s)** em 19/11/2026. Se o trabalho passar desse tempo, eu usaria uma fila e dividiria o processamento em etapas, salvando o progresso. Se precisar rodar em uma etapa só, usaria um job de contêiner. [Anúncio da AWS](https://aws.amazon.com/about-aws/whats-new/2026/11/aws-lambda-30-minute-ondemand-event-functions/)

### 4. Quanto custa um milhão de execuções com 128 MB e 100 ms?

Usei os preços de `us-east-1` dados na aula. A conta abaixo é antes da faixa gratuita.

| Parcela | Conta | Custo |
| --- | --- | --- |
| Memória e tempo | `128 ÷ 1.024 = 0,125 GB` e `100 ms = 0,1 s` | |
| Computação | `1.000.000 × 0,125 × 0,1 = 12.500 GB-s` | `12.500 × US$ 0,0000166667 = US$ 0,20833375` |
| Requisições | `1.000.000 ÷ 1.000.000 × US$ 0,20` | `US$ 0,20` |
| **Total** | `US$ 0,20833375 + US$ 0,20` | **US$ 0,40833375 ≈ US$ 0,41** |

O total soma as requisições e o tempo de computação do Lambda. [Preços da AWS](https://aws.amazon.com/lambda/pricing/)

### 5. Em que parte do Rota Vital essa função faz sentido?

Eu usaria essa função depois que o pedido fosse aprovado e as bolsas já estivessem separadas. Gerar a guia é rápido e pode acontecer quando chega uma requisição ou um evento. Já a reserva e a baixa das bolsas ficariam no serviço de estoque, que pode usar transações para impedir duas reservas da mesma bolsa. A guia recebe esses dados depois da confirmação.

## Uso de IA

Usei uma ferramenta da **OpenAI** como apoio no código Java, nos testes, no README e na preparação do vídeo. Para validar o projeto, foram executados os quatro testes com `mvn clean verify` e os experimentos locais com `curl`. [Evidências da execução](evidencias/)

## Referências

- [Google Cloud · Desenvolvimento local de funções](https://docs.cloud.google.com/run/docs/local-dev-functions)
- [Google Cloud · Functions Framework para Java](https://github.com/GoogleCloudPlatform/functions-framework-java)
