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
  const [shownSeq, setShownSeq] = useState<number>(-1);
  const [error, setError] = useState<string | null>(null);
  const shareUrl = `${location.origin}/#/game/${gameId}`;

  useEffect(() => {
    fetchState(gameId).then(setServerState).catch((e) => setError(String(e)));
    const unsub = subscribeToGame(gameId, setServerState);
    return unsub;
  }, [gameId]);

  // Reconcile: animate each new event in sequence, then update the displayed board.
  useEffect(() => {
    if (!serverState) return;
    if (spinning) return;
    if (serverState.lastEvent && serverState.eventSeq > shownSeq) {
      if (!viewState) setViewState(serverState);
      setSpinning(serverState.lastEvent);
      setShownSeq(serverState.eventSeq);
    } else {
      setViewState(serverState);
    }
  }, [serverState, viewState, spinning, shownSeq]);

  const onSpinDone = useCallback(() => {
    setSpinning(null);
  }, []);

  async function doMove(fromFile: number, fromRank: number, toFile: number, toRank: number) {
    setError(null);
    const result = await submitMove(gameId, playerId, fromFile, fromRank, toFile, toRank);
    if (typeof result === 'string') setError(result);
  }

  if (!viewState) {
    return (
      <div className="loading">
        Loading game {gameId}…
        {error && <p className="error">{error}</p>}
        {spinning && <Spinner event={spinning} onDone={onSpinDone} />}
      </div>
    );
  }

  const captured: Record<Color, PieceType[]> = { WHITE: [], BLACK: [] };
  for (const m of viewState.history) {
    if (m.captured) captured[m.mover === 'WHITE' ? 'BLACK' : 'WHITE'].push(m.captured);
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

  const turnBanner = turnActionBanner(viewState, myColor);
  const interactive =
    !spinning &&
    viewState.status === 'IN_PROGRESS' &&
    myColor === viewState.turn &&
    viewState.currentTurnAction !== 'SKIP' &&
    viewState.currentTurnAction !== 'AUTO';

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

      {turnBanner && <div className={'turn-banner ' + turnBanner.tone}>{turnBanner.text}</div>}

      <div className="play-area">
        <CapturedRow pieces={captured.BLACK} color="BLACK" />
        <Board
          state={viewState}
          myColor={myColor}
          interactive={interactive}
          onMove={doMove}
        />
        <CapturedRow pieces={captured.WHITE} color="WHITE" />
      </div>

      {error && <p className="error">{error}</p>}

      {spinning && <Spinner event={spinning} onDone={onSpinDone} />}
    </div>
  );
}

function turnActionBanner(
  state: GameState,
  myColor: Color | null
): { text: string; tone: string } | null {
  if (state.status !== 'IN_PROGRESS') return null;
  const action = state.currentTurnAction;
  if (!action || action === 'NORMAL') return null;
  const mine = myColor === state.turn;
  const who = mine ? 'You' : state.turn.toLowerCase();
  switch (action) {
    case 'DOUBLE':
      return {
        text: `Double turn: ${who} ${mine ? 'have' : 'has'} ${state.movesRemaining} move${state.movesRemaining === 1 ? '' : 's'} left.`,
        tone: 'good',
      };
    case 'SKIP':
      return { text: `${who} ${mine ? 'are' : 'is'} skipped this turn.`, tone: 'warn' };
    case 'FORCED':
      return {
        text: `Forced piece: ${who} must move the highlighted piece.`,
        tone: 'warn',
      };
    case 'AUTO':
      return { text: `Auto-move: the board moves for ${who.toLowerCase()}.`, tone: 'warn' };
    default:
      return null;
  }
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
