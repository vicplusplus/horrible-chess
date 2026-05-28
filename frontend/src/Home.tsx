import { useState } from 'react';
import { createGame, joinGame } from './api';
import type { JoinResponse } from './types';

interface Props {
  onJoined: (resp: JoinResponse) => void;
}

export function Home({ onJoined }: Props) {
  const [joinId, setJoinId] = useState('');
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function create() {
    setBusy(true);
    setErr(null);
    try {
      const resp = await createGame();
      onJoined(resp);
    } catch (e) {
      setErr(String(e));
    } finally {
      setBusy(false);
    }
  }

  async function join() {
    setBusy(true);
    setErr(null);
    try {
      const resp = await joinGame(joinId.trim());
      onJoined(resp);
    } catch (e) {
      setErr(String(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="home">
      <h1>Horrible Chess</h1>
      <p className="tagline">
        Chess, but cursed: randomized armies, dice-roll captures, hijacked
        turns, mystery squares, and meddling ducks. Take a king to win.
      </p>
      <div className="actions">
        <button onClick={create} disabled={busy}>
          Create new game
        </button>
        <div className="join">
          <input
            placeholder="Game ID"
            value={joinId}
            onChange={(e) => setJoinId(e.target.value)}
            disabled={busy}
          />
          <button onClick={join} disabled={busy || !joinId.trim()}>
            Join
          </button>
        </div>
      </div>
      {err && <p className="error">{err}</p>}
    </div>
  );
}
