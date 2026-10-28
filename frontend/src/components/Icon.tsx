import type { CSSProperties } from "react";

const paths = {
  chat: "M4 4h16v12H9l-5 4V4Z M8 8h8 M8 12h5",
  documents: "M6 3h8l4 4v14H6V3Z M14 3v5h4 M9 12h6 M9 16h6",
  plus: "M12 5v14 M5 12h14",
  settings:
    "M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8Z M12 2v3 M12 19v3 M2 12h3 M19 12h3 M5 5l2 2 M17 17l2 2 M5 19l2-2 M17 7l2-2",
  menu: "M4 6h16 M4 12h16 M4 18h16",
  close: "m6 6 12 12 M6 18 18 6",
  more: "M5 11v2 M12 11v2 M19 11v2",
  send: "m3 3 18 9-18 9 4-9-4-9Z M7 12h14",
  upload: "M12 16V3 m-5 5 5-5 5 5 M4 16v5h16v-5",
  check: "m5 12 4 4 10-10",
  info: "M12 16v-5 M12 7v1 M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0Z",
  user: "M12 3a4 4 0 1 0 0 8 4 4 0 0 0 0-8Z M4 21v-2a8 6 0 0 1 16 0v2",
  chevron: "m8 4 8 8-8 8",
} as const;

export function Icon({
  name,
  size = 20,
  style,
}: {
  name: keyof typeof paths;
  size?: number;
  style?: CSSProperties;
}) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.6"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
      style={style}
    >
      <path d={paths[name]} />
    </svg>
  );
}

export function RockyMark() {
  return (
    <svg
      width="32"
      height="32"
      viewBox="0 0 32 32"
      fill="none"
      aria-hidden="true"
    >
      <path
        d="m3 25 10-19 6 11 3-6 7 14H3Z"
        stroke="currentColor"
        strokeWidth="1.8"
        strokeLinejoin="round"
      />
      <path d="m9 14 4 3 4-3" stroke="currentColor" strokeWidth="1.8" />
    </svg>
  );
}
