package com.pi.demo.dto;

/** Resultado da varredura de inicializacao (camera, ESP e API de IA). */
public record VerificacaoDto(Item camera, Item esp, Item ia, boolean tudoOk) {

    public record Item(boolean ok, String mensagem) {
    }
}
