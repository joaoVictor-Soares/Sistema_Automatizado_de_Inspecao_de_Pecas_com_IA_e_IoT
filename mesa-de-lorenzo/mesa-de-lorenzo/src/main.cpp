#include <Arduino.h>
#include "pinos.h"

// Estado atual das 8 saídas (bit 0 = Q0 = J5 pino 1 ... bit 7 = Q7 = J5 pino 8)
static uint8_t estadoSaidas = 0x00;

// Envia o byte de estado ao 74HC595 e atualiza as saídas (latch)
void atualizarSaidas(uint8_t valor) {
  estadoSaidas = valor;
  digitalWrite(PIN_595_LATCH, LOW);
  shiftOut(PIN_595_DATA, PIN_595_CLOCK, MSBFIRST, valor);
  digitalWrite(PIN_595_LATCH, HIGH);
  digitalWrite(PIN_595_LATCH, LOW);
}

void inicializarSaidas() {
  // Como OE está fixo em GND, o 74HC595 liga com conteúdo aleatório.
  // Zera o registrador o mais cedo possível para desabilitar todas as saídas.
  pinMode(PIN_595_CLOCK, OUTPUT);
  pinMode(PIN_595_DATA, OUTPUT);
  pinMode(PIN_595_LATCH, OUTPUT);
  digitalWrite(PIN_595_CLOCK, LOW);
  digitalWrite(PIN_595_DATA, LOW);
  digitalWrite(PIN_595_LATCH, LOW);
  atualizarSaidas(0x00);
}

void inicializarMux() {
  pinMode(PIN_MUX_S0, OUTPUT);
  pinMode(PIN_MUX_S1, OUTPUT);
  pinMode(PIN_MUX_S2, OUTPUT);
  pinMode(PIN_MUX_S3, OUTPUT);
  digitalWrite(PIN_MUX_S0, LOW);
  digitalWrite(PIN_MUX_S1, LOW);
  digitalWrite(PIN_MUX_S2, LOW);
  digitalWrite(PIN_MUX_S3, LOW);
}

void setup() {
  inicializarSaidas();  // primeira ação: todas as saídas desligadas
  inicializarMux();

  Serial.begin(115200);
  Serial.println();
  Serial.println(F("Gateway IoT ESP8266 - saidas inicializadas desabilitadas"));
}

// ---------------------------------------------------------------------------
// Comando NOK: aciona a saída 0 por 3 segundos (não bloqueante)
// ---------------------------------------------------------------------------
constexpr uint8_t  SAIDA_NOK       = 0;
constexpr uint32_t TEMPO_NOK_MS    = 5000;

static bool     nokAtivo   = false;
static uint32_t nokInicio  = 0;
static String   bufferSerial;

void ligarSaida(uint8_t saida)    { atualizarSaidas(estadoSaidas | (1 << saida)); }
void desligarSaida(uint8_t saida) { atualizarSaidas(estadoSaidas & ~(1 << saida)); }

void tratarComando(String cmd) {
  cmd.trim();
  cmd.toUpperCase();
  if (cmd.length() == 0) return;

  if (cmd == "NOK") {
    ligarSaida(SAIDA_NOK);
    nokAtivo  = true;
    nokInicio = millis();  // novo NOK durante o acionamento reinicia os 3 s
    Serial.println(F("NOK recebido: saida 0 acionada por 3 s"));
  } else {
    Serial.print(F("Comando desconhecido: "));
    Serial.println(cmd);
  }
}

void lerSerial() {
  while (Serial.available()) {
    char c = Serial.read();
    if (c == '\n' || c == '\r') {
      tratarComando(bufferSerial);
      bufferSerial = "";
    } else if (bufferSerial.length() < 32) {
      bufferSerial += c;
    }
  }
}

void atualizarTemporizadorNok() {
  if (nokAtivo && (millis() - nokInicio >= TEMPO_NOK_MS)) {
    desligarSaida(SAIDA_NOK);
    nokAtivo = false;
    Serial.println(F("Saida 0 desacionada"));
  }
}

void loop() {
  lerSerial();
  atualizarTemporizadorNok();
}
