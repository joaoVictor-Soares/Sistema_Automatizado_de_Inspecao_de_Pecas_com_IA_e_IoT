# Alteracoes no backend para o firmware mesa-de-lorenzo (NOK, 115200 bps)

Os caminhos são relativos à raiz do repositório. Copie cada arquivo para o mesmo caminho no projeto
(substitui o arquivo existente). Nada foi commitado.

## Arquivos

| Arquivo | O que mudou |
|---|---|
| `backend-spring/demo/src/main/java/com/pi/demo/service/EspSerialGateway.java` | Reescrito. Envia `NOK\n` quando `escreverBit(enderecoCilindro, true)`; `false` não envia nada; outro endereço gera erro. Não interpreta mais frames `E/S`; respostas do ESP vão para o log como `[ESP] ...`. `lerBit()` sempre `false`. Espera 2,5 s após abrir a porta (reset do NodeMCU). |
| `backend-spring/demo/src/main/java/com/pi/demo/config/EspProperties.java` | Só o comentário da classe: protocolo e pinagem novos. Campos e construtor iguais. |
| `backend-spring/demo/src/main/resources/application.properties` | `esp.baud-rate` 9600 → 115200; `esp.tempo-acionamento-inicial-ms` 700 → 5000 (igual ao `TEMPO_NOK_MS` do firmware); `inspecao.polling-habilitado` true → false; comentários do bloco ESP atualizados. |
| `backend-spring/demo/src/test/java/com/pi/demo/TestSupport.java` | Valor simulado de baud 9600 → 115200 (linha do `esp()`). |
| `backend-spring/demo/src/test/java/com/pi/demo/service/AtuadorServiceImplTest.java` | Mesma troca de baud 9600 → 115200 na linha do `props`. |

## Onde conferir no código

- Envio do comando: `EspSerialGateway.escreverBit` → `enviarLinha("NOK")`.
- Abertura da porta e espera de boot: `EspSerialGateway.abrirPorta` (`ESPERA_BOOT_MS = 2500`).
- Constante do comando: `EspSerialGateway.COMANDO_NOK`.
- Tempo de acionamento: `application.properties`, chave `esp.tempo-acionamento-inicial-ms`.

## Efeitos colaterais a considerar

- Sem polling, nada dispara a inspeção automaticamente. O gatilho precisa ser definido (captura Java, câmera ou outro sinal).
- `AtuadorServiceImpl.acionarManual()` continua síncrono e espera o tempo configurado (agora 5 s).
- Documentos antigos (`docs/RASPBERRY_PI_SETUP.md`, `docs/ARQUITETURA_ARDUINO.md`) ainda citam 9600 bps. Não foram alterados.

## Verificação

- Não compilado com Maven nesta sandbox (dependências do Spring não baixam pelo proxy).
- Só checagem de sintaxe. Rode `mvn test` no PC do projeto.
