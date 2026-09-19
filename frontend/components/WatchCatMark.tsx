// The WatchCat mark (a cat's head drawn as a rounded shield, with a verified badge). The body and the details swap in dark mode, which is the
// brand's "reverse" mark; the eyes, nose and badge are always the accent teal. Minimum size 20px.
export default function WatchCatMark({ size = 28, className = '' }: { size?: number; className?: string }) {
  const body = 'rgb(var(--mark-body))';
  const detail = 'rgb(var(--mark-detail))';
  const teal = '#2FB6A7';
  return (
    <svg width={size} height={size} viewBox="0 0 100 100" className={className} role="img" aria-label="WatchCat">
      <polygon points="30,24 38,2 47,22" fill={body} />
      <polygon points="53,22 62,2 70,24" fill={body} />
      <polygon points="33,21 38,8 43,20" fill={detail} />
      <polygon points="57,20 62,8 67,21" fill={detail} />
      <path d="M50,14 C66,14 80,23 80,37 L80,54 C80,75 65,89 50,97 C35,89 20,75 20,54 L20,37 C20,23 34,14 50,14 Z" fill={body} />
      {[[21, 50, 2, 45], [20, 57, 1, 57], [21, 64, 2, 69], [79, 50, 98, 45], [80, 57, 99, 57], [79, 64, 98, 69]].map(([x1, y1, x2, y2], i) => (
        <line key={i} x1={x1} y1={y1} x2={x2} y2={y2} stroke={detail} strokeWidth="1.8" strokeLinecap="round" />
      ))}
      <ellipse cx="38" cy="45" rx="7" ry="5" fill={teal} />
      <line x1="38" y1="41" x2="38" y2="49" stroke={detail} strokeWidth="2.4" strokeLinecap="round" />
      <ellipse cx="62" cy="45" rx="7" ry="5" fill={teal} />
      <line x1="62" y1="41" x2="62" y2="49" stroke={detail} strokeWidth="2.4" strokeLinecap="round" />
      <polygon points="46,58 54,58 50,64" fill={teal} />
      <path d="M50,64 C46,69 41,70 37,67" fill="none" stroke={detail} strokeWidth="2.2" strokeLinecap="round" />
      <path d="M50,64 C54,69 59,70 63,67" fill="none" stroke={detail} strokeWidth="2.2" strokeLinecap="round" />
      <circle cx="77" cy="79" r="17" fill={teal} />
      <path d="M69,79 L75,85 L87,71" fill="none" stroke={detail} strokeWidth="5" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}
