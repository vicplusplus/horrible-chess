import { useEffect, useState } from 'react';
import { Game } from './Game';
import { Home } from './Home';
import { joinGame } from './api';
import type { Color, JoinResponse } from './types';

interface Session {
  gameId: string;
  playerId: string;
  color: Color | null;
}

const STORAGE_KEY = 'horrible-chess-session';

function loadSession(): Session | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    return raw ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
}

function saveSession(s: Session | null) {
  if (s) {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(s));
  } else {
    localStorage.removeItem(STORAGE_KEY);
  }
}

function parseHash(): string | null {
  const m = location.hash.match(/^#\/game\/([\w-]+)/);
  return m ? m[1] : null;
}

export function App() {
  const [session, setSession] = useState<Session | null>(loadSession);
  const [hashGameId, setHashGameId] = useState<string | null>(parseHash);
  const [autoJoinError, setAutoJoinError] = useState<string | null>(null);

  useEffect(() => {
    function onHash() { setHashGameId(parseHash()); }
    window.addEventListener('hashchange', onHash);
    return () => window.removeEventListener('hashchange', onHash);
  }, []);

  useEffect(() => {
    if (!hashGameId) return;
    if (session && session.gameId === hashGameId) return;
    // URL points to a game we're not in. Try to join it.
    let cancelled = false;
    (async () => {
      try {
        const resp = await joinGame(hashGameId);
        if (!cancelled) acceptJoin(resp);
      } catch (e) {
        if (!cancelled) setAutoJoinError(String(e));
      }
    })();
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [hashGameId]);

  function acceptJoin(resp: JoinResponse) {
    const s: Session = { gameId: resp.gameId, playerId: resp.playerId, color: resp.color };
    setSession(s);
    saveSession(s);
    location.hash = `#/game/${resp.gameId}`;
  }

  function leave() {
    saveSession(null);
    setSession(null);
    location.hash = '';
  }

  if (session) {
    return (
      <Game
        gameId={session.gameId}
        playerId={session.playerId}
        myColor={session.color}
        onLeave={leave}
      />
    );
  }

  return (
    <>
      <Home onJoined={acceptJoin} />
      {autoJoinError && <p className="error">{autoJoinError}</p>}
    </>
  );
}
