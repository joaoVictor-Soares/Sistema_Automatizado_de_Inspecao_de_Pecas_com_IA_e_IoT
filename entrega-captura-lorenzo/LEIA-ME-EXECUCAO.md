# Passo a passo - captura + refinamento na mesma maquina (Windows)

Tudo roda no mesmo PC: o ESP8266 na USB (COM7), o programa Java no terminal e o Docker
com o refinador. O Docker NAO acessa a COM7; so a pasta de dados e compartilhada.

Pasta de dados usada nos exemplos: `C:\projeto\dados` (vai conter aprovada\, reprovada\,
aprovada_refinada\ e reprovada_refinada\). Ajuste o caminho se for outro.

## 1. Gravar o firmware mesa-de-lorenzo (VS Code + PlatformIO)

1. Abra a pasta `mesa-de-lorenzo` no VS Code (com a extensao PlatformIO).
2. Conecte o NodeMCU. Confira a porta (Gerenciador de Dispositivos > Portas: COMx).
3. Terminal do PlatformIO:
   ```
   pio run -e nodemcuv2 -t upload --upload-port COM7
   ```
   (troque COM7 pela sua porta)
4. Abra o monitor serial a 115200 baud (`pio device monitor -b 115200 -p COM7`).
   Feche-o antes de rodar o Java: so um programa pode usar a COM7 por vez.

## 2. Testar a serial com o firmware (antes do Java)

No monitor, digite `NOK` e ENTER. Deve aparecer `NOK recebido: ...` e o rele/LED
da saida 0 deve acionar por cerca de 5 segundos. Digite `OI` e ENTER: deve aparecer
`Comando desconhecido: OI` (prova ida e volta sem acionar nada).

ATENCAO: o firmware diz "por 3 s" na mensagem, mas o tempo real e 5000 ms
(`TEMPO_NOK_MS = 5000` em `main.cpp`). Corrigir a mensagem ou o valor no firmware.

## 3. Java (captura, sem janela)

Pre-requisitos: JDK 17 ou superior e Maven (`java -version`, `mvn -v`).

```
cd C:\projeto\captura-java
mvn dependency:copy-dependencies -DoutputDirectory=libs
mkdir out
javac -cp "libs\*" -d out CapturaTampinha.java
```

Teste da comunicacao (nao aciona nada):
```
java -cp "out;libs\*" CapturaTampinha --testar-esp --porta COM7
```
Deve aparecer `[ESP] Comando desconhecido: OI`.

Teste do acionamento (ATENCAO: o cilindro/rele aciona por ~5 s):
```
java -cp "out;libs\*" CapturaTampinha --testar-esp --disparar --porta COM7
```

Execucao da captura:
```
java -cp "out;libs\*" CapturaTampinha --porta COM7 --saida C:\projeto\dados
```
- Quando a tampinha entrar na faixa vermelha, aparece `Rotule a peca`. Digite `a` (aprovada)
  ou `r` (reprovada) + ENTER dentro de 1,5 s. Sem tecla, vale a opcao `--padrao` (padrao: a).
- Reprovada: o Java envia `NOK` ao ESP depois de `--atraso-nok-ms` (padrao 4000 ms).
  Calibre esse tempo na mesa.
- `q` + ENTER encerra. Ctrl+C tambem funciona.

Para ver as opcoes: `java -cp "out;libs\*" CapturaTampinha --ajuda`.

## 4. Docker (refinador Python)

Pre-requisito: Docker Desktop instalado e rodando.

```
cd C:\projeto\refinador
docker build -t refinador-tampinhas .
docker run -d --name refinador --restart unless-stopped -v "C:\projeto\dados:/dados" refinador-tampinhas
```

Acompanhar:
```
docker logs -f refinador
```
Cada foto nova em `aprovada\` ou `reprovada\` gera `..._refinada.png` na pasta
`aprovada_refinada\` ou `reprovada_refinada\`.

Processar so o que ja existe e encerrar (teste):
```
docker run --rm -v "C:\projeto\dados:/dados" refinador-tampinhas --raiz /dados --uma-vez
```

Parar: `docker stop refinador`. Para rodar sem Docker (teste local):
```
py -m pip install -r requirements.txt
py refinador_tampinhas.py --raiz C:\projeto\dados --uma-vez
```

## 5. Ordem de execucao

1. Ligar o ESP (USB) e gravar o firmware (passo 1).
2. Fechar o monitor serial.
3. Rodar o teste de comunicacao (passo 3, `--testar-esp`).
4. Subir o Docker (passo 4): o refinador fica observando a pasta.
5. Rodar a captura Java (passo 3, execucao).

## 6. Erros comuns

| Sintoma | Causa provavel |
|---|---|
| `nao foi possivel abrir COM7` | Monitor serial ou IDE aberto usando a porta, ou porta errada |
| `[ESP]` nao aparece nada no teste | Firmware nao gravado, baud diferente de 115200 ou cabo so de carga |
| `[CAMERA] falha ao abrir` | Indice da camera errado (`--camera 1`) ou outro programa usando a camera |
| Docker: pasta vazia | Caminho do `-v` errado ou pasta nao compartilhada no Docker Desktop (Settings > Resources > File sharing) |
| Sem `_refinada` | Arquivo ainda sendo gravado (espera 1,5 s) ou arquivo corrompido (ver `docker logs`) |

## 7. Limites desta entrega

- Os rotulos vem do teclado. A decisao pelo servidor PyTorch ainda nao esta integrada.
- O Java NAO foi compilado nesta sandbox (sem Maven/JavaCV aqui). Compile no PC do projeto e
  envie o erro exato se aparecer.
- O backend Spring (classes `Esp*`) ainda usa o protocolo antigo (`S/E`, 9600 bps).
  Alterar isso exige autorizacao explicita (regra da Entrega 2).
