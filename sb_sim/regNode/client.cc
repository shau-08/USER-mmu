// Copyright (c) 2024 Zero ASIC Corporation
// This code is licensed under Apache License 2.0 (see LICENSE for details)

#include "switchboard.hpp"
#include "tilelinklib.h"
#include <iostream>
#include <thread>
#define NBYTES 32

void client_thread(SBTX *sb_tlA, SBRX *sb_tlD) {
  sb_packet packetA;

  TLMessageA *tl_a = (TLMessageA *)packetA.data;
  tl_a->opcode = PutFullData;
  tl_a->param = 0;
  tl_a->size = 5; // 8 bytes
  tl_a->corrupt = 0;
  tl_a->source = 0;
  tl_a->address = 0;
  tl_a->mask = 0xFFFFFFFF;
  for (int i = 0; i < 32; i++) {
    tl_a->data[i] = i;
  }

  packetA.destination = 0xbeefcafe;
  packetA.last = true;

  // send packet
  sb_tlA->send_blocking(packetA);
  fprintf(stderr, "client TX A packet: %s\n", sb_packet_to_str(packetA, NBYTES).c_str());

  //TLMessageA *tl_a = (TLMessageA *)packetA.data;
  tl_a->opcode = PutFullData;
  tl_a->param = 0;
  tl_a->size = 5; // 8 bytes
  tl_a->corrupt = 0;
  tl_a->source = 1;
  tl_a->address = 0;
  tl_a->mask = 0xFFFFFFFF;
  for (int i = 0; i < 32; i++) {
    tl_a->data[i] = i;
  }

  packetA.destination = 0xbeefcafe;
  packetA.last = true;

  // send packet
  sb_tlA->send_blocking(packetA);
  fprintf(stderr, "client TX A packet: %s\n", sb_packet_to_str(packetA, NBYTES).c_str());

  sb_packet packetD;
  while (sb_tlD->recv(packetD) == false)
    ;
  fprintf(stderr, "Client RX D packet: %s\n", sb_packet_to_str(packetD, NBYTES).c_str());
  TLMessageD *tl_d = (TLMessageD *)packetD.data;
  assert(tl_d->opcode == AccessAck);
  assert(tl_d->denied == 0);

  //sb_packet packetD;
  while (sb_tlD->recv(packetD) == false)
    ;
  fprintf(stderr, "Client RX D packet: %s\n", sb_packet_to_str(packetD, NBYTES).c_str());
  //TLMessageD *tl_d = (TLMessageD *)packetD.data;
  assert(tl_d->opcode == AccessAck);
  assert(tl_d->denied == 0);
}

int main() {
  SBTX client_0_a;
  SBRX client_0_d;

  // initialize connections
  fprintf(stderr, "client_0_a initializing\n");
  client_0_a.init("client_0_a.q");
  fprintf(stderr, "client_0_d initializing\n");
  client_0_d.init("client_0_d.q");

  // form packet
  std::thread tClient0(client_thread, &client_0_a, &client_0_d);

  tClient0.join();
  // send a packet that will end the test

  /*
  for (int i = 0; i < NBYTES; i++) {
    txp.data[i] = 0xff;
  }
  tx.send_blocking(txp);
  */

  // declare test as having passed for regression testing purposes

  printf("PASS!\n");

  return 0;
}
