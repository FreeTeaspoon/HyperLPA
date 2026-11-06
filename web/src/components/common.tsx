import { useEffect, useRef, useState } from "react";
import { Check, Copy, LoaderCircle, X } from "lucide-react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Dialog, DialogClose, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Field, FieldDescription, FieldLabel } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import type { ComponentProps, ReactNode } from "react";

class FieldValidationError extends Error {
  constructor(readonly field: string, message: string) { super(message); }
}
export async function validateField<T>(field: string, check: () => T | Promise<T>): Promise<T> {
  try { return await check(); }
  catch (error) { throw new FieldValidationError(field, error instanceof Error ? error.message : "Check this value and try again."); }
}
export function useFormErrors() {
  const [failure, setFailure] = useState<{ message: string; field?: string }>();
  useEffect(() => {
    if (failure?.field) document.getElementById(failure.field)?.focus();
  }, [failure]);
  return {
    error: failure?.message ?? "", field: failure?.field,
    clear: () => setFailure(undefined),
    report: (error: unknown) => setFailure({ message: error instanceof Error ? error.message : "Something went wrong. Try again.",
      field: error instanceof FieldValidationError ? error.field : undefined }),
    props: (id: string) => ({ "aria-invalid": failure?.field === id || undefined,
      "aria-describedby": failure?.field === id ? id + "-error" : undefined }),
    message: (id: string) => failure?.field === id ? <FieldDescription id={id + "-error"} className="text-destructive">{failure.message}</FieldDescription> : null,
  };
}

export function CopyButton({ value, label = "Copy" }: { value: string; label?: string }) {
  const [copied, setCopied] = useState(false);
  const timer = useRef<ReturnType<typeof setTimeout>>(undefined);
  useEffect(() => () => clearTimeout(timer.current), []);
  return <Button variant="ghost" size="icon" aria-label={copied ? "Copied" : label} title={label} onClick={async () => {
    try {
      await navigator.clipboard.writeText(value); setCopied(true); clearTimeout(timer.current);
      timer.current = setTimeout(() => setCopied(false), 1400); toast.success("Copied");
    } catch { toast.error("Clipboard access failed. Select the value and copy it manually."); }
  }}><span className="copy-glyph" data-copied={copied}><Copy /><Check /></span></Button>;
}
export function BusyButton({ busy, children, ...props }: ComponentProps<typeof Button> & { busy?: boolean }) {
  return <Button {...props} disabled={busy || props.disabled}>{busy && <LoaderCircle className="spin" data-icon="inline-start" />}{children}</Button>;
}
export function TextField({ label, hint, id, children, "aria-describedby": describedBy, ...props }: ComponentProps<typeof Input> & { label: string; hint?: string }) {
  const inputId = id ?? label.toLowerCase().replaceAll(" ", "-");
  return <Field><FieldLabel htmlFor={inputId}>{label}</FieldLabel><Input id={inputId} aria-describedby={[hint ? inputId + "-hint" : "", describedBy].filter(Boolean).join(" ") || undefined} {...props} />
    {hint && <FieldDescription id={inputId + "-hint"}>{hint}</FieldDescription>}{children}</Field>;
}
export function Modal({ open, onClose, title, description, children, footer, wide, returnFocus }: {
  open: boolean; onClose: () => void; title: string; description?: string; children?: ReactNode; footer?: ReactNode; wide?: boolean;
  returnFocus?: HTMLElement | null;
}) {
  // The caller retains content until Radix has completed its exit animation.
  return <Dialog open={open} onOpenChange={value => { if (!value) onClose(); }}><DialogContent className={wide ? "dialog-wide" : undefined} showCloseButton={false}
    onCloseAutoFocus={event => { event.preventDefault(); (returnFocus?.isConnected ? returnFocus : document.getElementById("main"))?.focus({ preventScroll: true }); }}>
    <DialogHeader><DialogTitle>{title}</DialogTitle><DialogDescription className={description ? undefined : "sr-only"}>{description ?? title}</DialogDescription></DialogHeader>
    <DialogClose asChild><Button variant="ghost" size="icon-sm" className="dialog-close" aria-label="Close"><X /></Button></DialogClose>
    {children && <div className="dialog-body">{children}</div>}
    {footer && <DialogFooter>{footer}</DialogFooter>}
  </DialogContent></Dialog>;
}
