import { useCallback, useEffect, useState } from 'react';
import { Board } from './Board';
import { Spinner } from './Spinner';
import { fetchState, submitMove, subscribeToGame } from './api';
import { glyph } from './pieces';
import type { Color, GameState, PieceType, RandomEvent } from './types';

interface Props {
  gameId: string;
  playerId: string;
  myColor: Color | null;
  onLeave: () => void;
}

export function Game({ gameId, playerId, myColor, onLeave }: Props) {
  const [serverState, setServerState] = useState<GameState | null>(null);
  const [viewState, setViewState] = useState<GameState | null>(null);
  const [spinning, setSpinning] = useState<RandomEvent | null>(null);
  const [error, setError] = useState<string | null>(null);
  const shareUrl = `${location.origin}/#/game/${gameId}`;

  useEffect(() => {
    fetchState(gameId).then(setServerState).catch((e) => setError(String(e)));
    const unsub = subscribeToGame(gameId, setServerState);
    return unsub;
  }, [gameId]);

  // Reconcile incoming server state with what's displayed. New event => spin first.
  useEffect(() => {
    if (!serverState) return;
    if (!viewState) {
      setViewState(serverState);
      return;
    }
    if (spinning) return; // already spinning; will reconcile when it finishes
    if (serverState.eventSeq > viewState.eventSeq && serverState.lastEvent) {
      setSpinning(serverState.lastEvent);
    } else {
      setViewState(serverState);
    }
  }, [serverState, viewState, spinning]);

  const onSpinDone = useCallback(() => {
    setSpinning(null);
    setViewState(serverState);
  }, [serverState]);

  async function doMove(fromFile: number, fromRank: number, toFile: number, toRank: number) {
    setError(null);
    const result = await submitMove(gameId, playerId, fromFile, fromRank, toFile, toRank);
    if (typeof result === 'string') {
      setError(result);
    }
    // Successful moves arrive via WebSocket; no need to update state here.
  }

  if (!viewState) {
    return (
      <div className="loading">
        Loading game {gameId}…
        {error && <p className="error">{error}</p>}
      </div>
    );
  }

  const captured: Record<Color, PieceType[]> = { WHITE: [], BLACK: [] };
  for (const m of viewState.history) {
    if (m.captured) {
      captured[m.mover === 'WHITE' ? 'BLACK' : 'WHITE'].push(m.captured);
    }
  }

  let statusText = '';
  if (viewState.status === 'WAITING_FOR_OPPONENT') {
    statusText = 'Waiting for opponent…';
  } else if (viewState.status === 'IN_PROGRESS') {
    statusText =
      myColor === viewState.turn
        ? `Your turn (${viewState.turn.toLowerCase()})`
        : `${viewState.turn.toLowerCase()}'s turn`;
  } else if (viewState.status === 'WHITE_WINS') {
    statusText = 'Black is out of kings. White wins!';
  } else if (viewState.status === 'BLACK_WINS') {
    statusText = 'White is out of kings. Black wins!';
  }

  const interactive = !spinning && viewState.status === 'IN_PROGRESS';

  return (
    <div className="game">
      <div className="topbar">
        <button className="leave" onClick={onLeave}>
          ← Leave
        </button>
        <div className="info">
          <div className="status">{statusText}</div>
          <div className="meta">
            Game <code>{viewState.id}</code>
            {myColor && <> · you are <strong>{myColor.toLowerCase()}</strong></>}
          </div>
        </div>
        <div className="share">
          <span>Share:</span>
          <input readOnly value={shareUrl} onClick={(e) => (e.target as HTMLInputElement).select()} />
        </div>
      </div>

      <div className="play-area">
        <CapturedRow pieces={captured.BLACK} color="BLACK" />
        <Board state={viewState} myColor={myColor} interactive={interactive} onMove={doMove} />
        <CapturedRow pieces={captured.WHITE} color="WHITE" />
      </div>

      {error && <p className="error">{error}</p>}

      {spinning && <Spinner event={spinning} onDone={onSpinDone} />}
    </div>
  );
}

function CapturedRow({ pieces, color }: { pieces: PieceType[]; color: Color }) {
  if (pieces.length === 0) {
    return <div className="captured empty">no captures</div>;
  }
  return (
    <div className="captured">
      {pieces.map((p, i) => (
        <span key={i} className={'piece ' + color.toLowerCase()}>
          {glyph(color, p)}
        </span>
      ))}
    </div>
  );
}
