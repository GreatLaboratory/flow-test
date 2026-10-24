INSERT INTO extension (name, type, blocked) VALUES
  ('bat','FIXED',false), ('cmd','FIXED',false), ('com','FIXED',false), ('cpl','FIXED',false),
  ('exe','FIXED',false), ('scr','FIXED',false), ('js','FIXED',false)
ON CONFLICT (name) DO NOTHING;
