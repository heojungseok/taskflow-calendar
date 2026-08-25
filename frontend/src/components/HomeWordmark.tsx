import {useLayoutEffect, useRef} from 'react';

const SLOT_X = 256;
const SLOT_Y = 14;
const SLOT_WIDTH = 11;
const SLOT_HEIGHT = 8.5;
const CAP_HEIGHT = {
  transformBox: 'fill-box',
  transformOrigin: 'center bottom',
  transform: 'scaleY(1.08)',
} as const;

export default function HomeWordmark({onSlotLayout}: {onSlotLayout: (rect: DOMRect) => void}) {
  const slotRef = useRef<SVGRectElement>(null);

  useLayoutEffect(() => {
    const measure = () => {
      if (slotRef.current) onSlotLayout(slotRef.current.getBoundingClientRect());
    };

    measure();
    void document.fonts.ready.then(measure);
    window.addEventListener('resize', measure);
    return () => window.removeEventListener('resize', measure);
  }, [onSlotLayout]);

  return (
    <svg
      className="pointer-events-none block w-full overflow-visible"
      viewBox="0 0 720 112"
      role="img"
      aria-label="TaskFlow"
    >
      <defs>
        <pattern id="home-wordmark-slots" width="15" height="13" patternUnits="userSpaceOnUse">
          <rect x="1" y="1" width={SLOT_WIDTH} height={SLOT_HEIGHT} rx="0.75" fill="var(--ink)" />
        </pattern>
        <mask id="home-wordmark-f-slot" maskUnits="userSpaceOnUse" x="0" y="0" width="720" height="112">
          <rect width="720" height="112" fill="white" />
          <rect x={SLOT_X} y={SLOT_Y} width={SLOT_WIDTH} height={SLOT_HEIGHT} fill="black" />
        </mask>
      </defs>

      <text
        x="0"
        y="88"
        fontFamily="Archivo, Arial, sans-serif"
        fontSize="112"
        fontWeight="800"
        letterSpacing="-7"
        fill="url(#home-wordmark-slots)"
        mask="url(#home-wordmark-f-slot)"
      >
        <tspan data-wordmark-char="T" style={CAP_HEIGHT}>T</tspan>
        <tspan data-wordmark-char="a">a</tspan>
        <tspan data-wordmark-char="s">s</tspan>
        <tspan data-wordmark-char="k">k</tspan>
        <tspan data-wordmark-char="F" style={CAP_HEIGHT}>F</tspan>
        <tspan data-wordmark-char="l">l</tspan>
        <tspan data-wordmark-char="o">o</tspan>
        <tspan data-wordmark-char="w">w</tspan>
      </text>

      <rect
        data-testid="home-wordmark-slot"
        ref={slotRef}
        x={SLOT_X}
        y={SLOT_Y}
        width={SLOT_WIDTH}
        height={SLOT_HEIGHT}
        rx="0.75"
        fill="var(--ink)"
      />
    </svg>
  );
}
