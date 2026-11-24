package android.view; public abstract class ViewGroup extends View implements ViewParent { public ViewGroup(android.content.Context c){super(c);} public void addView(View v){} public int getChildCount(){return 0;} public View getChildAt(int i){return null;}
 public static class LayoutParams { public LayoutParams(int w,int h){} }
 public static class MarginLayoutParams extends LayoutParams { public int leftMargin; public MarginLayoutParams(int w,int h){super(w,h);} public int getMarginStart(){return 0;} public void setMarginStart(int s){} } }
