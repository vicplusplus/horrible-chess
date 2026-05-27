import { Client } from '@stomp/stompjs';
import type { GameState, JoinResponse, PieceType } from './types';

const API = '/api/games';

export async function createGame(): Promise<JoinResponse> {
  const r = await fetch(API, { method: 'POST' });
  if (!r.ok) throw new Error(await r.text());
  return r.json();
}

export async function joinGame(gameId: string): Promise<JoinResponse> {
  const r = await fetch(`${API}/${gameId}/join`, { method: 'POST' });
  if (!r.ok) throw new Error(await r.text());
  return r.json();
}

export async function fetchState(gameId: string): Promise<GameState> {
  const r = await fetch(`${API}/${gameId}`);
  if (!r.ok) throw new Error(await r.text());
  return r.json();
}

export async function submitMove(
  gameId: string,
  playerId: string,
  fromFile: number,
  fromRank: number,
  toFile: number,
  toRank: number,
  promotion: PieceType | null
): Promise<GameState | string> {
  const r = await fetch(`${API}/${gameId}/move`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ playerId, fromFile, fromRank, toFile, toRank, promotion }),
  });
  if (!r.ok) return r.text();
  return r.json();
}

export function subscribeToGame(
  gameId: string,
  onState: (s: GameState) => void
): () => void {
  const wsUrl = `${location.protocol === 'https:' ? 'wss:' : 'ws:'}//${location.host}/ws`;
  const client = new Client({
    brokerURL: wsUrl,
    reconnectDelay: 2000,
  });
  client.onConnect = () => {
    client.subscribe(`/topic/game/${gameId}`, (msg) => {
      onState(JSON.parse(msg.body));
    });
  };
  client.activate();
  return () => {
    client.deactivate();
  };
}
