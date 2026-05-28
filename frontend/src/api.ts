import { Client } from '@stomp/stompjs';
import type { GameState, JoinResponse } from './types';

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

// Frames with frameSeq > since, in order, so the client can replay the ones it
// missed (backgrounded tab / dropped socket) instead of snapping to the latest.
export async function fetchFrames(gameId: string, since: number): Promise<GameState[]> {
  const r = await fetch(`${API}/${gameId}/frames?since=${since}`);
  if (!r.ok) throw new Error(await r.text());
  return r.json();
}

export async function submitMove(
  gameId: string,
  playerId: string,
  fromFile: number,
  fromRank: number,
  toFile: number,
  toRank: number
): Promise<GameState | string> {
  const r = await fetch(`${API}/${gameId}/move`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ playerId, fromFile, fromRank, toFile, toRank, promotion: null }),
  });
  if (!r.ok) return r.text();
  return r.json();
}

export function subscribeToGame(
  gameId: string,
  onState: (s: GameState) => void,
  onReconnect?: () => void
): () => void {
  const wsUrl = `${location.protocol === 'https:' ? 'wss:' : 'ws:'}//${location.host}/ws`;
  const client = new Client({
    brokerURL: wsUrl,
    reconnectDelay: 2000,
    // Match the server's 15s broker heartbeat so proxies (e.g. Cloudflare,
    // which closes idle WebSockets after ~100s) keep the connection open even
    // when nobody is moving.
    heartbeatIncoming: 15000,
    heartbeatOutgoing: 15000,
  });
  let connectedBefore = false;
  client.onConnect = () => {
    client.subscribe(`/topic/game/${gameId}`, (msg) => {
      onState(JSON.parse(msg.body));
    });
    // On a *re*connect (after a dropped socket) we may have missed broadcasts
    // during the gap — the simple broker doesn't replay. Let the caller catch
    // up the frames it missed.
    if (connectedBefore) onReconnect?.();
    connectedBefore = true;
  };
  client.activate();
  return () => {
    client.deactivate();
  };
}
