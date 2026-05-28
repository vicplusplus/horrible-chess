import { useEffect, useMemo, useRef, useState } from 'react';
import type { Color, RandomEvent } from './types';

interface Props {
  event: RandomEvent;
  actor: Color | null;
  myColor: Color | null;
  onDone: () => void;
}

const KIND_LABEL: Record<RandomEvent['kind'], string> = {
  FIRST_MOVER: 'First Move',
  PROMOTION: 'Promotion',
  CAPTURE_STANDOFF: 'Piece Standoff',
  TURN_ACTION: 'Turn',
  PIECE_SELECTION: 'Piece Selection',
  SQUARE_EVENT: 'Mystery Square',
};

function turnLabel(actor: Color | null, myColor: Color | null): string {
  if (!actor) return 'Turn';
  if (actor === myColor) return 'Your Turn';
  return actor === 'WHITE' ? "White's Turn" : "Black's Turn";
}

const ITEM_WIDTH = 120;
const TARGET_INDEX = 40;
const STRIP_LENGTH = 50;
const SPIN_MS = 2500;

export function Spinner({ event, actor, myColor, onDone }: Props) {
  const [revealed, setRevealed] = useState(false);
  const viewportRef = useRef<HTMLDivElement | null>(null);
  const [offset, setOffset] = useState(0);

  const strip = useMemo(() => {
    const items: string[] = [];
    for (let i = 0; i < STRIP_LENGTH; i++) {
      if (i === TARGET_INDEX) {
        items.push(event.outcome);
      } else {
        const pool = event.possibleOutcomes;
        items.push(pool[Math.floor(Math.random() * pool.length)]);
      }
    }
    return items;
  }, [event]);

  useEffect(() => {
    const start = -ITEM_WIDTH * 2;
    setOffset(start);
    const w = viewportRef.current?.offsetWidth ?? 500;
    const target = -(TARGET_INDEX * ITEM_WIDTH) + w / 2 - ITEM_WIDTH / 2;

    const startTimer = window.setTimeout(() => setOffset(target), 50);
    const revealTimer = window.setTimeout(() => setRevealed(true), SPIN_MS + 100);
    const doneTimer = window.setTimeout(onDone, SPIN_MS + 1300);

    return () => {
      window.clearTimeout(startTimer);
      window.clearTimeout(revealTimer);
      window.clearTimeout(doneTimer);
    };
  }, [event, onDone]);

  const bad = event.outcome === 'Failed' || event.outcome === 'Got taken';
  const neutral = event.outcome === 'Nothing happens';
  const punct = bad || neutral ? '.' : '!';

  return (
    <div className="spinner-overlay" role="dialog" aria-label="Random event spinner">
      <div className="spinner-label">
        {event.kind === 'TURN_ACTION'
          ? turnLabel(actor, myColor)
          : KIND_LABEL[event.kind] ?? event.kind}
      </div>
      <div className="spinner-viewport" ref={viewportRef}>
        <div
          className="spinner-strip"
          style={{
            transform: `translateX(${offset}px)`,
            transition:
              offset === -ITEM_WIDTH * 2
                ? 'none'
                : `transform ${SPIN_MS}ms cubic-bezier(0.1, 0.7, 0.1, 1)`,
          }}
        >
          {strip.map((label, i) => (
            <div key={i} className="spinner-item">
              {label}
            </div>
          ))}
        </div>
      </div>
      <div
        className={
          'spinner-result' +
          (revealed ? ' revealed' : '') +
          (bad ? ' failed' : '') +
          (neutral ? ' neutral' : '')
        }
      >
        {revealed ? event.outcome + punct : ' '}
      </div>
    </div>
  );
}
