export const C = {
  bg: '#0B0B0D',
  card: '#141418',
  cardAlt: '#1A1A20',
  border: '#26262E',
  text: '#ECECEC',
  sub: '#9A9AA3',
  muted: '#6B6B75',
  gold: '#D4AF37',
  goldSoft: '#F5D76E',
  up: '#26A69A',
  down: '#EF5350',
  warn: '#FFA726',
  info: '#4FC3F7',
};

export const actionColor = (a: string | undefined) => (a === 'BUY' ? C.up : a === 'SELL' ? C.down : C.gold);

export const stateColor = (st: string) =>
  st === 'TP2' || st === 'TP1' ? C.up : st === 'SL' ? C.down : st === 'ACTIVE' || st === 'TP1_PARTIAL' ? C.info
    : st === 'ARMED' ? C.gold : st === 'REJECTED' ? '#555' : C.warn;
