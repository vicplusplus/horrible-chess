import { useCallback, useEffect, useRef, useState } from 'react';
import { Board } from './Board';
import { MoveLog } from './MoveLog';
import { Spinner } from './Spinner';
import { fetchFrames, fetchState, submitMove, subscribeToGame } from './api';
import { glyph } from './pieces';
import type { Color, GameState, JournalEntry, PieceType, RandomEvent } from './types';

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
  // Highest frameSeq we've already enqueued. Dedups overlap between live socket
  // frames and a catch-up fetch, and tells catchUp where to resume from.
  const lastFrameSeqRef = useRef<number>(-1);
  const initedRef = useRef(false);
  const [queueTick, setQueueTick] = useState(0);
  const [viewState, setViewState] = useState<GameState | null>(null);
  const [spinning, setSpinning] = useState<{ event: RandomEvent; actor: Color | null } | null>(null);
  const [shownSeq, setShownSeq] = useState<number>(-1);
  const [inited, setInited] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const holdTimerRef = useRef<number | null>(null);
  // Journal frozen at its pre-spin contents so the log doesn't print an
  // event's result while the spinner is still revealing it.
  const shownJournalRef = useRef<JournalEntry[]>([]);
  const shareUrl = `${location.origin}/#/game/${gameId}`;

  const enqueue = useCallback((s: GameState) => {
    if (s.frameSeq <= lastFrameSeqRef.current) return; // already seen
    lastFrameSeqRef.current = s.frameSeq;
    queueRef.current.push(s);
    setQueueTick((t) => t + 1);
  }, []);

  // First load: establish the baseline at the latest frame without replaying
  // the whole game. A fresh viewer has no prior position, so there's nothing to
  // animate — we just snap to "now". Keep any live frames that already raced
  // ahead of the baseline so we don't drop them.
  const initialLoad = useCallback(() => {
    fetchState(gameId)
      .then((s) => {
        queueRef.current = queueRef.current.filter((f) => f.frameSeq > s.frameSeq);
        if (s.frameSeq > lastFrameSeqRef.current) lastFrameSeqRef.current = s.frameSeq;
        setViewState(s);
        setShownSeq(s.eventSeq);
        setInited(true);
        initedRef.current = true;
        setQueueTick((t) => t + 1);
      })
      .catch((e) => setError(String(e)));
  }, [gameId]);

  // Catch up on missed frames (backgrounded tab / dropped socket): fetch every
  // frame after the one we last applied and feed them through the normal queue,
  // so the reconciler animates 5→6→7→8 from where we are — no snapping.
  const catchUp = useCallback(() => {
    fetchFrames(gameId, lastFrameSeqRef.current)
      .then((frames) => frames.forEach(enqueue))
      .catch((e) => setError(String(e)));
  }, [gameId, enqueue]);

  useEffect(() => {
    initialLoad();
    // Fires only on a *re*connect (the socket dropped and came back) — that's
    // when we replay whatever was broadcast during the gap.
    const unsub = subscribeToGame(gameId, enqueue, () => {
      if (initedRef.current) catchUp();
    });
    return () => {
      unsub();
      if (holdTimerRef.current != null) {
        window.clearTimeout(holdTimerRef.current);
        holdTimerRef.current = null;
      }
    };
  }, [gameId, enqueue, initialLoad, catchUp]);

  // Regaining focus: the socket may have been suspended while backgrounded.
  useEffect(() => {
    function onVisible() {
      if (document.visibilityState === 'visible' && initedRef.current) catchUp();
    }
    document.addEventListener('visibilitychange', onVisible);
    return () => document.removeEventListener('visibilitychange', onVisible);
  }, [catchUp]);

  // Drain one state per cycle. Re-runs on queueTick (new state arrived), when
  // the spinner finishes (spinning → null), and once the initial snapshot has
  // established the baseline shownSeq. Frames that arrived before init wait in
  // the queue until then, so we never spin for events older than the baseline.
  useEffect(() => {
    if (!inited) return;
    if (spinning) return;
    if (holdTimerRef.current != null) return;
    const queue = queueRef.current;
    if (queue.length === 0) return;
    // Skip frames at or behind the baseline (e.g. the snapshot we already
    // painted, or a stale frame that raced the initial fetch).
    let next = queue.shift()!;
    while (next.eventSeq < shownSeq && queue.length > 0) {
      next = queue.shift()!;
    }
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
  }, [queueTick, spinning, shownSeq, inited]);

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

  // Only advance the visible log between spins; while a spinner is up, keep
  // showing the journal as it was before the event so the outcome isn't spoiled.
  if (!spinning) shownJournalRef.current = viewState.journal;

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

      {/* Fixed-height slot so showing/hiding the banner never shifts the board. */}
      <div className="turn-banner-slot">
        {turnBanner && (
          <div className={'turn-banner ' + turnBanner.tone}>{turnBanner.text}</div>
        )}
      </div>

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
        <MoveLog journal={shownJournalRef.current} />
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
    case 'FORCED': {
      const n = state.forcedPiecePositions.length;
      return {
        text:
          n === 1
            ? `Forced piece: ${who} must move the highlighted piece.`
            : `Forced pieces: ${who} must move one of the ${n} highlighted pieces.`,
        tone: 'warn',
      };
    }
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
