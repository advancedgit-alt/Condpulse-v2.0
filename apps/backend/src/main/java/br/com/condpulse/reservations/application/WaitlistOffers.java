package br.com.condpulse.reservations.application;

import java.util.UUID;

/**
 * Oferta da fila em transação própria, depois do commit do comando que liberou o período (ou antes de avaliar o
 * período). Falha da oferta nunca desfaz o comando: a próxima passada (consulta, criação, worker) oferece de novo.
 */
public interface WaitlistOffers {
 void offerForAmenity(UUID actor,UUID tenant,UUID amenity);
 void offerForReservation(UUID actor,UUID tenant,UUID reservation);
 /** Sem fila (testes de unidade e caminhos que não liberam período). */
 WaitlistOffers NONE=new WaitlistOffers() {
  @Override public void offerForAmenity(UUID actor,UUID tenant,UUID amenity) {}
  @Override public void offerForReservation(UUID actor,UUID tenant,UUID reservation) {}
 };
}
