import type { SVGProps } from "react";

type IconProps = SVGProps<SVGSVGElement>;

const baseProps = {
  viewBox: "0 0 24 24",
  fill: "none",
  stroke: "currentColor",
  strokeWidth: 1.8,
  strokeLinecap: "round" as const,
  strokeLinejoin: "round" as const,
  "aria-hidden": true,
};

export function DocumentIcon(props: IconProps) {
  return (
    <svg {...baseProps} {...props}>
      <path d="M6 2.8h8.2L19 7.6v13.6H6z" />
      <path d="M14 2.8v5h5M9 12h7M9 15.5h7M9 19h5" />
    </svg>
  );
}

export function GridIcon(props: IconProps) {
  return (
    <svg {...baseProps} {...props}>
      <rect x="4" y="4" width="6" height="6" rx="1" />
      <rect x="14" y="4" width="6" height="6" rx="1" />
      <rect x="4" y="14" width="6" height="6" rx="1" />
      <rect x="14" y="14" width="6" height="6" rx="1" />
    </svg>
  );
}

export function UsersIcon(props: IconProps) {
  return (
    <svg {...baseProps} {...props}>
      <circle cx="9" cy="8" r="3.2" />
      <path d="M3.8 19c.4-3.2 2.3-5 5.2-5s4.8 1.8 5.2 5M15 5.3a3.2 3.2 0 0 1 0 6.1M16.5 14c2.1.5 3.3 2.1 3.7 4.5" />
    </svg>
  );
}

export function PipelineIcon(props: IconProps) {
  return (
    <svg {...baseProps} {...props}>
      <circle cx="5" cy="6" r="2" />
      <circle cx="19" cy="12" r="2" />
      <circle cx="5" cy="18" r="2" />
      <path d="M7 6h3.5a3 3 0 0 1 3 3v0a3 3 0 0 0 3 3H17M7 18h3.5a3 3 0 0 0 3-3" />
    </svg>
  );
}

export function SearchIcon(props: IconProps) {
  return (
    <svg {...baseProps} {...props}>
      <circle cx="10.8" cy="10.8" r="6.5" />
      <path d="m16 16 4 4" />
    </svg>
  );
}

export function ChatIcon(props: IconProps) {
  return (
    <svg {...baseProps} {...props}>
      <path d="M4 4.5h16v11H9l-5 4z" />
      <path d="M8 9h8M8 12.5h5" />
    </svg>
  );
}

export function GraphIcon(props: IconProps) {
  return (
    <svg {...baseProps} {...props}>
      <circle cx="5" cy="6" r="2.2" />
      <circle cx="18.5" cy="5.5" r="2.2" />
      <circle cx="12" cy="18" r="2.2" />
      <path d="m7 6 9.3-.4M6.2 7.8l4.6 8.4M17.4 7.3l-4.2 8.8" />
    </svg>
  );
}

export function MemoryIcon(props: IconProps) {
  return (
    <svg {...baseProps} {...props}>
      <path d="M8.2 5.3a4.2 4.2 0 0 1 7.6 2.5 4 4 0 0 1 1.3 7.7 3.8 3.8 0 0 1-6.7 2.4A4.1 4.1 0 0 1 6 14a4 4 0 0 1 2.2-8.7Z" />
      <path d="M10 7.5v9M14 7.5v9M10 10.5h4M10 13.5h4" />
    </svg>
  );
}

export function CloseIcon(props: IconProps) {
  return (
    <svg {...baseProps} {...props}>
      <path d="M5 5l14 14M19 5 5 19" />
    </svg>
  );
}

export function EyeIcon({ crossed = false, ...props }: IconProps & { crossed?: boolean }) {
  return (
    <svg {...baseProps} {...props}>
      <path d="M2.8 12s3.2-5 9.2-5 9.2 5 9.2 5-3.2 5-9.2 5-9.2-5-9.2-5Z" />
      <circle cx="12" cy="12" r="2.2" />
      {crossed ? <path d="M4 4l16 16" /> : null}
    </svg>
  );
}

export function ShieldIcon(props: IconProps) {
  return (
    <svg {...baseProps} {...props}>
      <path d="M12 2.8 19 6v5.2c0 4.5-2.8 8.2-7 10-4.2-1.8-7-5.5-7-10V6z" />
      <path d="m8.8 12 2.1 2.1 4.4-4.5" />
    </svg>
  );
}

export function UploadIcon(props: IconProps) {
  return (
    <svg {...baseProps} {...props}>
      <path d="M12 16V4M7.5 8.5 12 4l4.5 4.5M5 14.5v5h14v-5" />
    </svg>
  );
}

export function DownloadIcon(props: IconProps) {
  return (
    <svg {...baseProps} {...props}>
      <path d="M12 4v12M7.5 11.5 12 16l4.5-4.5M5 19.5h14" />
    </svg>
  );
}
