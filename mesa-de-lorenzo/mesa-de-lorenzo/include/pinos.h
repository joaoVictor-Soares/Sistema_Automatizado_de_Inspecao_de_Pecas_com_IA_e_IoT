#pragma once
#include <Arduino.h>

// ---------------------------------------------------------------------------
// Mapeamento de pinos conforme esquema "Gateway_IoT_ESP8266" (rev. 1)
// ---------------------------------------------------------------------------

// Registrador de deslocamento 74HC595 (U5) -> ULN2803 (U6) -> J5 (8 saídas)
// MR ligado em 3V3 e OE ligado em GND no hardware: as saídas ficam sempre
// habilitadas, por isso o firmware precisa zerar o registrador logo no boot.
constexpr uint8_t PIN_595_CLOCK = D6;  // SH_CP (pino 11) - GPIO12
constexpr uint8_t PIN_595_DATA  = D7;  // DS    (pino 14) - GPIO13
constexpr uint8_t PIN_595_LATCH = D8;  // ST_CP (pino 12) - GPIO15

constexpr uint8_t NUM_SAIDAS = 8;

// Multiplexador analógico 16 canais (U3) -> entradas J2 (C8..C15) e J3 (C0..C7)
// EN ligado em GND no hardware (sempre habilitado).
constexpr uint8_t PIN_MUX_S0  = D0;  // GPIO16
constexpr uint8_t PIN_MUX_S1  = D3;  // GPIO0  (pino de boot - deve estar em HIGH no reset)
constexpr uint8_t PIN_MUX_S2  = D4;  // GPIO2  (pino de boot - deve estar em HIGH no reset)
constexpr uint8_t PIN_MUX_S3  = D5;  // GPIO14
constexpr uint8_t PIN_MUX_SIG = A0;  // AD0

constexpr uint8_t NUM_ENTRADAS = 16;

// Barramento de expansão J4 (I2C / Serial)
constexpr uint8_t PIN_SDA = D2;  // GPIO4
constexpr uint8_t PIN_SCL = D1;  // GPIO5
