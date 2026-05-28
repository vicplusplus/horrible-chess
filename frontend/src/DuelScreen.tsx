import { useEffect, useState } from 'react';
import { glyph } from './pieces';
import type { Color, RandomEvent } from './types';

interface Props {
  event: RandomEvent;
  actor: Color | null;
  myColor: Color | null;
  onDone: () => void;
}

const CLASH_MS = 1700;
const HOLD_MS = 1500;

export function DuelScreen({ event, onDone }: Props) {
  const [revealed, setRevealed] = useState(false);

  useEffect(() => {
    setRevealed(false);
    const revealTimer = window.setTimeout(() => setRevealed(true), CLASH_MS);
    const doneTimer = window.setTimeout(onDone, CLASH_MS + HOLD_MS);
    return () => {
      window.clearTimeout(revealTimer);
      window.clearTimeout(doneTimer);
    };
  }, [event, onDone]);

  const duel = event.duel;
  if (!duel) return null;

  const outcome = event.outcome; // Takes | Nothing happens | Got taken
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
        <div className={'duel-arena' + (revealed ? ' revealed' : '')}>
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
          {revealed ? resultText(outcome) : ' '}
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
