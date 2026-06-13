import { useEffect, useMemo, useRef, useState } from 'react';
import { glyph } from './pieces';
import type { Color, RandomEvent } from './types';

interface Props {
  event: RandomEvent;
  actor: Color | null;
  myColor: Color | null;
  onDone: () => void;
}

// The standoff plays in three beats: a slot reel rolls to the outcome, the two
// pieces then clash, and finally the winner/loser resolve. Keeping the reel on
// screen through the clash avoids a layout jump and keeps the verdict visible.
const TARGET_INDEX = 36;
const STRIP_LENGTH = 46;
const START_OFFSET = -240;
const REEL_MS = 1900; // reel spin duration
const CLASH_MS = 950; // pieces lunge after the reel lands
const HOLD_MS = 1300; // hold the resolved board before dismissing

type Phase = 'reel' | 'clash' | 'result';

export function DuelScreen({ event, onDone }: Props) {
  const [phase, setPhase] = useState<Phase>('reel');
  const viewportRef = useRef<HTMLDivElement | null>(null);
  const stripRef = useRef<HTMLDivElement | null>(null);
  const [offset, setOffset] = useState(START_OFFSET);

  const outcome = event.outcome; // Takes | Nothing happens | Got taken

  const strip = useMemo(() => {
    const items: string[] = [];
    for (let i = 0; i < STRIP_LENGTH; i++) {
      if (i === TARGET_INDEX) {
        items.push(outcome);
      } else {
        const pool = event.possibleOutcomes;
        items.push(pool[Math.floor(Math.random() * pool.length)]);
      }
    }
    return items;
  }, [event, outcome]);

  useEffect(() => {
    setPhase('reel');
    setOffset(START_OFFSET);
    // Measure the rendered item width so the reel lands centered on every
    // screen size (CSS shrinks items on mobile).
    const firstItem = stripRef.current?.querySelector<HTMLElement>('.spinner-item');
    const itemWidth = firstItem?.offsetWidth ?? 120;
    const viewportWidth = viewportRef.current?.offsetWidth ?? 500;
    const target = -(TARGET_INDEX * itemWidth) + viewportWidth / 2 - itemWidth / 2;

    const startTimer = window.setTimeout(() => setOffset(target), 50);
    const clashTimer = window.setTimeout(() => setPhase('clash'), REEL_MS + 200);
    const resultTimer = window.setTimeout(() => setPhase('result'), REEL_MS + 200 + CLASH_MS);
    const doneTimer = window.setTimeout(onDone, REEL_MS + 200 + CLASH_MS + HOLD_MS);
    return () => {
      window.clearTimeout(startTimer);
      window.clearTimeout(clashTimer);
      window.clearTimeout(resultTimer);
      window.clearTimeout(doneTimer);
    };
  }, [event, onDone]);

  const duel = event.duel;
  if (!duel) return null;

  const revealed = phase === 'result';
  const attackerWins = outcome === 'Takes';
  const defenderWins = outcome === 'Got taken';
  const nothing = outcome === 'Nothing happens';

  const pieceClass = (side: 'attacker' | 'defender', color: Color, wins: boolean, loses: boolean) =>
    'duel-piece ' +
    side +
    ' ' +
    color.toLowerCase() +
    (revealed && wins ? ' winner' : '') +
    (revealed && loses ? ' loser' : '');

  return (
    <div className="duel-overlay" role="dialog" aria-label="Piece standoff">
      <div className="duel-card">
        <div className="duel-title">Piece Standoff</div>

        <div className="spinner-viewport duel-reel" ref={viewportRef}>
          <div
            className="spinner-strip"
            ref={stripRef}
            style={{
              transform: `translateX(${offset}px)`,
              transition:
                offset === START_OFFSET
                  ? 'none'
                  : `transform ${REEL_MS}ms cubic-bezier(0.1, 0.7, 0.1, 1)`,
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
            'duel-arena' +
            (phase === 'clash' ? ' clashing' : '') +
            (revealed ? ' revealed' : '')
          }
        >
          <div className={pieceClass('attacker', duel.attackerColor, attackerWins, defenderWins)}>
            <span className="duel-glyph">{glyph(duel.attackerColor, duel.attackerPiece)}</span>
            <span className="duel-role">Attacker</span>
          </div>
          <div className="duel-vs">{revealed ? '⚔' : 'VS'}</div>
          <div className={pieceClass('defender', duel.defenderColor, defenderWins, attackerWins)}>
            <span className="duel-glyph">{glyph(duel.defenderColor, duel.defenderPiece)}</span>
            <span className="duel-role">Defender</span>
          </div>
        </div>

        <div
          className={
            'duel-result' +
            (revealed ? ' revealed' : '') +
            (defenderWins ? ' bad' : '') +
            (nothing ? ' neutral' : '')
          }
        >
          {revealed ? resultText(outcome) : ' '}
        </div>
      </div>
    </div>
  );
}

function resultText(outcome: string): string {
  switch (outcome) {
    case 'Takes':
      return 'Takes! The attacker wins.';
    case 'Got taken':
      return 'Got taken! The attacker falls.';
    case 'Nothing happens':
      return 'Nothing happens.';
    default:
      return outcome;
  }
}
