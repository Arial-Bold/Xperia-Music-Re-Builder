package android.app;
public class AlertDialog { public static class Builder {
  public Builder(android.content.Context c) {}
  public Builder setTitle(CharSequence t) { return this; }
  public Builder setMessage(CharSequence t) { return this; }
  public Builder setSingleChoiceItems(CharSequence[] items, int checked, android.content.DialogInterface.OnClickListener l) { return this; }
  public Builder setAdapter(android.widget.ListAdapter a, android.content.DialogInterface.OnClickListener l) { return this; }
  public Builder setPositiveButton(CharSequence t, android.content.DialogInterface.OnClickListener l) { return this; }
  public Builder setPositiveButton(int id, android.content.DialogInterface.OnClickListener l) { return this; }
  public Builder setNegativeButton(CharSequence t, android.content.DialogInterface.OnClickListener l) { return this; }
  public Builder setNegativeButton(int id, android.content.DialogInterface.OnClickListener l) { return this; }
  public AlertDialog show() { return null; } } }
