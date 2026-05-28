import { useEffect, useRef } from 'react';
import type { JournalEntry } from './types';

interface Props {
  journal: JournalEntry[];
}

export function MoveLog({ journal }: Props) {
  const scrollRef = useRef<HTMLDivElement>(null);
  const entries = journal ?? [];

  useEffect(() => {
    const el = scrollRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [entries.length]);

  return (
    <div className="movelog">
      <div className="movelog-title">Turn history</div>
      <div className="movelog-scroll" ref={scrollRef}>
        {entries.length === 0 ? (
          <div className="movelog-empty">No events yet.</div>
        ) : (
          <ul className="movelog-list">
            {entries.map((e, i) => (
              <li
                key={i}
                className={
                  'movelog-entry ' +
                  kindClass(e.kind) +
                  (e.color ? ' ' + e.color.toLowerCase() : '') +
                  (i === entries.length - 1 ? ' latest' : '')
                }
              >
                {e.text}
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}

function kindClass(kind: JournalEntry['kind']): string {
  return 'kind-' + kind.toLowerCase().replace('_', '-');
}
