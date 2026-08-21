export const MOTION_EASE_OUT = [0.23, 1, 0.32, 1] as const;

export const MOTION = {
  enter: { duration: 0.2, ease: MOTION_EASE_OUT },
  exit: { duration: 0.14, ease: MOTION_EASE_OUT },
  state: { duration: 0.14, ease: MOTION_EASE_OUT },
} as const;
