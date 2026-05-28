import { useEffect, useRef, useState } from 'react';
import type { CSSProperties } from 'react';
import { glyph } from './pieces';
import type { Color, GameState, LegalMove, PieceDto } from './types';

interface Props {
  state: GameState;
  myColor: Color | null;
  interactive: boolean;
  onMove: (
    fromFile: number,
    fromRank: number,
    toFile: number,
    toRank: number
  ) => void;
}

const FILES = ['a', 'b', 'c', 'd', 'e', 'f', 'g', 'h'];

// Duration of the slide; must stay under Game's INTER_FRAME_HOLD_MS so the
// animation finishes before the next queued board state is drained.
const MOVE_ANIM_MS = 240;
// Square-event flourishes (spawn / vanish / color-swap) run a touch longer.
const FX_MS = 480;
const CLEAR_MS = Math.max(MOVE_ANIM_MS, FX_MS) + 60;

interface MoveAnim {
  id: string;
  glyph: string;
  colorClass: string;
  // Destination square position, in board-percent.
  x: number;
  y: number;
  // Translate-in offset from the origin square, in board-percent.
  dx: number;
  dy: number;
}

// A board change that isn't a slide: a piece vanished (capture / explosion /
// random capture), appeared (spawn), or flipped colour in place (color swap).
interface FxEffect {
  id: string;
  kind: 'vanish' | 'spawn' | 'swap';
  glyph: string;
  colorClass: string;
  x: number;
  y: number;
}

export function Board({ state, myColor, interactive, onMove }: Props) {
  const [selected, setSelected] = useState<{ file: number; rank: number } | null>(null);

  const flipped = myColor === 'BLACK';
  const rankOrder = flipped ? [0, 1, 2, 3, 4, 5, 6, 7] : [7, 6, 5, 4, 3, 2, 1, 0];
  const fileOrder = flipped ? [7, 6, 5, 4, 3, 2, 1, 0] : [0, 1, 2, 3, 4, 5, 6, 7];

  // --- Move animations -----------------------------------------------------
  // Diff the previous board against the new one and slide any piece that moved
  // from its old square to its new one. Each sliding piece is rendered at its
  // DESTINATION and a CSS @keyframes animation translates it in from the old
  // square — playing on mount, so it's robust against rapid re-renders (no
  // imperative rAF toggling that a follow-up state could interrupt). The static
  // piece at the destination is hidden until the slide completes.
  const [anims, setAnims] = useState<MoveAnim[]>([]);
  const [fx, setFx] = useState<FxEffect[]>([]);
  const prevSquaresRef = useRef<(PieceDto | null)[][] | null>(null);
  const hiddenRef = useRef<Set<string>>(new Set());
  const clearTimerRef = useRef<number | null>(null);

  // Square offset in board-percent (each square is 12.5% of the board).
  const colPct = (f: number) => fileOrder.indexOf(f) * 12.5;
  const rowPct = (r: number) => rankOrder.indexOf(r) * 12.5;

  useEffect(() => {
    const prev = prevSquaresRef.current;
    prevSquaresRef.current = state.squares;
    if (!prev) return; // first render — nothing to animate from

    const removed: { f: number; r: number; piece: PieceDto }[] = [];
    const added: { f: number; r: number; piece: PieceDto }[] = [];
    for (let f = 0; f < 8; f++) {
      for (let r = 0; r < 8; r++) {
        const pp = prev[f][r];
        const np = state.squares[f][r];
        const same = pp && np && pp.type === np.type && pp.color === np.color;
        if (pp && !same) removed.push({ f, r, piece: pp });
        if (np && !same) added.push({ f, r, piece: np });
      }
    }

    const rid = () => Math.random().toString(36).slice(2, 7);
    const newAnims: MoveAnim[] = [];
    const newFx: FxEffect[] = [];
    const hidden = new Set<string>();
    const usedRemoved = new Set<number>();
    const usedAdded = new Set<number>();

    // 1) Pair a vanished piece with an identical one elsewhere → a slide.
    for (let ai = 0; ai < added.length; ai++) {
      const a = added[ai];
      const idx = removed.findIndex(
        (rm, i) =>
          !usedRemoved.has(i) &&
          rm.piece.type === a.piece.type &&
          rm.piece.color === a.piece.color &&
          !(rm.f === a.f && rm.r === a.r)
      );
      if (idx < 0) continue;
      usedRemoved.add(idx);
      usedAdded.add(ai);
      const rm = removed[idx];
      newAnims.push({
        id: `mv-${rm.f},${rm.r}->${a.f},${a.r}-${state.eventSeq}-${rid()}`,
        glyph: glyph(a.piece.color, a.piece.type),
        colorClass: a.piece.color.toLowerCase(),
        x: colPct(a.f),
        y: rowPct(a.r),
        // Offset in element-widths (1 square = 100%), so CSS translate(%) — which
        // is relative to the element's own box — lands exactly one square per step.
        dx: (fileOrder.indexOf(rm.f) - fileOrder.indexOf(a.f)) * 100,
        dy: (rankOrder.indexOf(rm.r) - rankOrder.indexOf(a.r)) * 100,
      });
      hidden.add(`${a.f},${a.r}`);
    }

    // 2) Remaining added: a color swap (same square also lost a piece) or a spawn.
    for (let ai = 0; ai < added.length; ai++) {
      if (usedAdded.has(ai)) continue;
      const a = added[ai];
      const swapIdx = removed.findIndex(
        (rm, i) => !usedRemoved.has(i) && rm.f === a.f && rm.r === a.r
      );
      const kind: FxEffect['kind'] = swapIdx >= 0 ? 'swap' : 'spawn';
      if (swapIdx >= 0) usedRemoved.add(swapIdx);
      newFx.push({
        id: `${kind}-${a.f},${a.r}-${state.eventSeq}-${rid()}`,
        kind,
        glyph: glyph(a.piece.color, a.piece.type),
        colorClass: a.piece.color.toLowerCase(),
        x: colPct(a.f),
        y: rowPct(a.r),
      });
      // Hide the static piece while it scales/flips in.
      hidden.add(`${a.f},${a.r}`);
    }

    // 3) Remaining removed: a piece simply vanished (capture / explosion / etc).
    for (let i = 0; i < removed.length; i++) {
      if (usedRemoved.has(i)) continue;
      const rm = removed[i];
      newFx.push({
        id: `vanish-${rm.f},${rm.r}-${state.eventSeq}-${rid()}`,
        kind: 'vanish',
        glyph: glyph(rm.piece.color, rm.piece.type),
        colorClass: rm.piece.color.toLowerCase(),
        x: colPct(rm.f),
        y: rowPct(rm.r),
      });
    }

    if (newAnims.length === 0 && newFx.length === 0) return;
    hiddenRef.current = hidden;
    setAnims(newAnims);
    setFx(newFx);
    if (clearTimerRef.current != null) window.clearTimeout(clearTimerRef.current);
    clearTimerRef.current = window.setTimeout(() => {
      clearTimerRef.current = null;
      hiddenRef.current = new Set();
      setAnims([]);
      setFx([]);
    }, CLEAR_MS);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [state.squares]);

  useEffect(
    () => () => {
      if (clearTimerRef.current != null) window.clearTimeout(clearTimerRef.current);
    },
    []
  );

  const forced = state.forcedPiecePosition;
  const isForcedSquare = (f: number, r: number) =>
    forced != null && forced.file === f && forced.rank === r;
  const isEventSquare = (f: number, r: number) =>
    state.eventSquares.some((p) => p.file === f && p.rank === r);
  const duckAt = (f: number, r: number) =>
    state.ducks.find((d) => d.position.file === f && d.position.rank === r);

  const movesFromSelected: LegalMove[] = selected
    ? state.legalMoves.filter(
        (m) => m.fromFile === selected.file && m.fromRank === selected.rank
      )
    : [];

  // For each legal move, the square to highlight. Castling moves (king sliding
  // 2+ files) get rewritten to highlight the rook the king is castling with,
  // since the king's actual destination depends on rook placement and is
  // confusing on randomized back rows.
  const highlights = new Map<string, 'move' | 'capture' | 'castle'>();
  for (const m of movesFromSelected) {
    const movingPiece = state.squares[m.fromFile][m.fromRank];
    let f = m.toFile;
    let r = m.toRank;
    let kind: 'move' | 'capture' | 'castle' = 'move';
    if (movingPiece?.type === 'KING' && Math.abs(m.toFile - m.fromFile) >= 2) {
      const dir = Math.sign(m.toFile - m.fromFile);
      f = m.toFile + dir;
      kind = 'castle';
    } else if (state.squares[f][r]) {
      kind = 'capture';
    }
    highlights.set(`${f},${r}`, kind);
  }

  function castleTargetFromRook(rookFile: number, rookRank: number): LegalMove | null {
    if (!selected) return null;
    const king = state.squares[selected.file][selected.rank];
    if (king?.type !== 'KING') return null;
    if (rookRank !== selected.rank) return null;
    const dir = Math.sign(rookFile - selected.file);
    if (dir === 0) return null;
    const kingDest = rookFile - dir;
    return (
      movesFromSelected.find(
        (m) => m.toFile === kingDest && m.toRank === rookRank
      ) ?? null
    );
  }

  function onSquareClick(file: number, rank: number) {
    if (!interactive) return;
    if (state.status !== 'IN_PROGRESS') return;
    if (myColor !== state.turn) return;
    const piece = state.squares[file][rank];
    if (selected) {
      if (selected.file === file && selected.rank === rank) {
        setSelected(null);
        return;
      }
      // Click on own rook with king selected: castle if legal.
      if (piece && piece.color === myColor && piece.type === 'ROOK') {
        const castle = castleTargetFromRook(file, rank);
        if (castle) {
          onMove(selected.file, selected.rank, castle.toFile, castle.toRank);
          setSelected(null);
          return;
        }
      }
      const moving = state.squares[selected.file][selected.rank];
      if (moving && moving.color === myColor) {
        onMove(selected.file, selected.rank, file, rank);
        setSelected(null);
        return;
      }
      setSelected(null);
    }
    if (piece && piece.color === myColor) {
      // If a forced piece is set, only it can be selected.
      if (forced && !isForcedSquare(file, rank)) return;
      setSelected({ file, rank });
    }
  }

  const myTurn =
    state.status === 'IN_PROGRESS' && myColor != null && myColor === state.turn;

  return (
    <div className={'board-wrap' + (myTurn ? ' my-turn' : '')}>
      <div className="board">
        {rankOrder.map((rank) => (
          <div className="board-row" key={rank}>
            {fileOrder.map((file) => {
              const rawPiece = state.squares[file][rank];
              const piece = hiddenRef.current.has(`${file},${rank}`) ? null : rawPiece;
              const isSelected = selected?.file === file && selected?.rank === rank;
              const light = (file + rank) % 2 === 1;
              const event = isEventSquare(file, rank);
              const duck = duckAt(file, rank);
              const highlight = highlights.get(`${file},${rank}`);
              return (
                <div
                  key={file}
                  className={
                    'square ' +
                    (light ? 'light' : 'dark') +
                    (isSelected ? ' selected' : '') +
                    (isForcedSquare(file, rank) ? ' forced' : '') +
                    (event ? ' event' : '') +
                    (duck ? ' duck' : '') +
                    (highlight ? ` hl-${highlight}` : '')
                  }
                  onClick={() => onSquareClick(file, rank)}
                >
                  {piece && (
                    <span className={'piece ' + piece.color.toLowerCase()}>
                      {glyph(piece.color, piece.type)}
                    </span>
                  )}
                  {!piece && event && <span className="event-marker">?</span>}
                  {highlight === 'move' && !piece && <span className="move-dot" />}
                  {highlight === 'capture' && <span className="capture-ring" />}
                  {highlight === 'castle' && <span className="castle-ring" />}
                  {duck && (
                    <span className="duck-marker" title={`Blocks for ${duck.turnsRemaining} more turn${duck.turnsRemaining === 1 ? '' : 's'}`}>
                      🦆
                    </span>
                  )}
                  {file === fileOrder[0] && (
                    <span className="rank-label">{rank + 1}</span>
                  )}
                  {rank === rankOrder[rankOrder.length - 1] && (
                    <span className="file-label">{FILES[file]}</span>
                  )}
                </div>
              );
            })}
          </div>
        ))}
        {(anims.length > 0 || fx.length > 0) && (
          <div className="anim-layer">
            {anims.map((a) => (
              <span
                key={a.id}
                className={'anim-piece piece ' + a.colorClass}
                style={
                  {
                    left: a.x + '%',
                    top: a.y + '%',
                    '--dx': a.dx + '%',
                    '--dy': a.dy + '%',
                  } as CSSProperties
                }
              >
                {a.glyph}
              </span>
            ))}
            {fx.map((e) => (
              <span
                key={e.id}
                className={`fx-piece fx-${e.kind} piece ${e.colorClass}`}
                style={{ left: e.x + '%', top: e.y + '%' }}
              >
                {e.glyph}
              </span>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
