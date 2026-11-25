# No reflection-dependent libraries in this app; defaults suffice, with one exception below.

# Glance tells widget providers apart by their GlanceAppWidget class: updateAll() finds the
# placed widgets of a class, and GlanceAppWidgetManager stores the class name per receiver.
# The look-only subclasses (PondWidget, BubblesWidget, EggTimerWidget) differ only in a
# constructor argument, so without this rule R8 merges them into one class: every
# Lily pads, Bubbles and Egg timer widget then draws the same picture. Keep each one a class
# of its own, under its own name, so the stored names also survive future builds.
-keep class * extends androidx.glance.appwidget.GlanceAppWidget
