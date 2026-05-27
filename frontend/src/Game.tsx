import { useEffect, useState } from 'react';
import { Board } from './Board';
import { fetchState, submitMove, subscribeToGame } from './api';
import { glyph } from './pieces';
import type { Color, GameState, PieceType } from './types';

interface Props {
  gameId: string;
  playerId: string;
  myColor: Color | null;
  onLeave: () => void;
}

export function Game({ gameId, playerId, myColor, onLeave }: Props) {
  const [state, setState] = useState<GameState | null>(null);
  const [error, setError] = useState<string | null>(null);
  const shareUrl = `${location.origin}/#/game/${gameId}`;

  useEffect(() => {
    fetchState(gameId).then(setState).catch((e) => setError(String(e)));
    const unsub = subscribeToGame(gameId, setState);
    return unsub;
  }, [gameId]);

  if (!state) {
    return (
      <div className="loading">
        Loading game {gameId}…
        {error && <p className="error">{error}</p>}
      </div>
    );
  }

  async function doMove(
    fromFile: number,
    fromRank: number,
    toFile: number,
    toRank: number,
    promotion: PieceType | null
  ) {
    setError(null);
    const result = await submitMove(gameId, playerId, fromFile, fromRank, toFile, toRank, promotion);
    if (typeof result === 'string') {
      setError(result);
    } else {
      setState(result);
    }
  }

  const captured: Record<Color, PieceType[]> = { WHITE: [], BLACK: [] };
  for (const m of state.history) {
    if (m.captured) captured[m.mover === 'WHITE' ? 'BLACK' : 'WHITE'].push(m.captured);
  }

  let statusText = '';
  if (state.status === 'WAITING_FOR_OPPONENT') {
    statusText = 'Waiting for opponent…';
  } else if (state.status === 'IN_PROGRESS') {
    statusText =
      myColor === state.turn
        ? `Your turn (${state.turn.toLowerCase()})`
        : `${state.turn.toLowerCase()}'s turn`;
  } else if (state.status === 'WHITE_WINS') {
    statusText = 'White captured the king. White wins!';
  } else if (state.status === 'BLACK_WINS') {
    statusText = 'Black captured the king. Black wins!';
  }

  return (
    <div className="game">
      <div className="topbar">
        <button className="leave" onClick={onLeave}>
          ← Leave
        </button>
        <div className="info">
          <div className="status">{statusText}</div>
          <div className="meta">
            Game <code>{state.id}</code>
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
        <Board state={state} myColor={myColor} onMove={doMove} />
        <CapturedRow pieces={captured.WHITE} color="WHITE" />
      </div>

      {error && <p className="error">{error}</p>}
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
