import { useCallback, useEffect, useRef, useState } from 'react';
import { Board } from './Board';
import { MoveLog } from './MoveLog';
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

// Hold each intermediate (non-event) state for this long before draining the
// next one, so board updates between spinners are actually visible.
const INTER_FRAME_HOLD_MS = 350;

export function Game({ gameId, playerId, myColor, onLeave }: Props) {
  // Queue of incoming server states. The reconciler drains one at a time,
  // pausing whenever a state introduces a new random event (which triggers a
  // spinner). This preserves event order across rapid server bursts — e.g.
  // SKIP → AUTO chains, or a SQUARE_EVENT cascade — that React's setState
  // batching would otherwise coalesce into "just the latest snapshot".
  const queueRef = useRef<GameState[]>([]);
  const [queueTick, setQueueTick] = useState(0);
  const [viewState, setViewState] = useState<GameState | null>(null);
  const [spinning, setSpinning] = useState<{ event: RandomEvent; actor: Color | null } | null>(null);
  const [shownSeq, setShownSeq] = useState<number>(-1);
  const [error, setError] = useState<string | null>(null);
  const holdTimerRef = useRef<number | null>(null);
  const shareUrl = `${location.origin}/#/game/${gameId}`;

  const enqueue = useCallback((s: GameState) => {
    queueRef.current.push(s);
    setQueueTick((t) => t + 1);
  }, []);

  useEffect(() => {
    fetchState(gameId)
      .then((s) => {
        // Snap to the current snapshot on first load — don't replay history.
        setViewState(s);
        setShownSeq(s.eventSeq);
      })
      .catch((e) => setError(String(e)));
    const unsub = subscribeToGame(gameId, enqueue);
    return () => {
      unsub();
      if (holdTimerRef.current != null) {
        window.clearTimeout(holdTimerRef.current);
        holdTimerRef.current = null;
      }
    };
  }, [gameId, enqueue]);

  // Drain one state per cycle. Re-runs on queueTick (new state arrived) and
  // when the spinner finishes (spinning → null).
  useEffect(() => {
    if (spinning) return;
    if (holdTimerRef.current != null) return;
    const queue = queueRef.current;
    if (queue.length === 0) return;
    const next = queue.shift()!;
    setViewState(next);
    if (next.lastEvent && next.eventSeq > shownSeq) {
      // Prefer the side captured on the event itself; the snapshot's turn may
      // already have advanced (e.g. SKIP flips it before this frame is sent).
      setSpinning({ event: next.lastEvent, actor: next.lastEvent.subject ?? next.turn });
      setShownSeq(next.eventSeq);
    } else if (queue.length > 0) {
      // Hold the new board briefly so the player can see the change before
      // we move on to the next event in the queue.
      holdTimerRef.current = window.setTimeout(() => {
        holdTimerRef.current = null;
        setQueueTick((t) => t + 1);
      }, INTER_FRAME_HOLD_MS);
    }
  }, [queueTick, spinning, shownSeq]);

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
        <button className="leave" onClick={onLeave}>
          ← Back to home
        </button>
        {error ? (
          <>
            <p>Couldn't load game {gameId}.</p>
            <p className="error">{error}</p>
          </>
        ) : (
          <p>Loading game {gameId}…</p>
        )}
        {spinning && (
          <Spinner
            event={spinning.event}
            actor={spinning.actor}
            myColor={myColor}
            onDone={onSpinDone}
          />
        )}
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
        <div className="board-column">
          <CapturedRow pieces={captured.BLACK} color="BLACK" />
          <Board
            state={viewState}
            myColor={myColor}
            interactive={interactive}
            onMove={doMove}
          />
          <CapturedRow pieces={captured.WHITE} color="WHITE" />
        </div>
        <MoveLog journal={viewState.journal} />
      </div>

      {error && <p className="error">{error}</p>}

      {spinning && (
        <Spinner
          event={spinning.event}
          actor={spinning.actor}
          myColor={myColor}
          onDone={onSpinDone}
        />
      )}
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
