package br.com.cofre.domain;

/** Metadados públicos de seleção; nunca inclui senha, notas ou lista de origens. */
public record AccountMatch(String id, String title, String username) {}
