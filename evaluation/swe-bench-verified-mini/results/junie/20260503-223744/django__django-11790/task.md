Solve this SWE-bench issue in the current repository.

Instance: django__django-11790
Repository: django/django
Base commit: b1d6b35e146aea83b171c1b921178bbaae2795ed

Problem statement:
AuthenticationForm's username field doesn't set maxlength HTML attribute.
Description
	
AuthenticationForm's username field doesn't render with maxlength HTML attribute anymore.
Regression introduced in #27515 and 5ceaf14686ce626404afb6a5fbd3d8286410bf13.
​https://groups.google.com/forum/?utm_source=digest&utm_medium=email#!topic/django-developers/qnfSqro0DlA
​https://forum.djangoproject.com/t/possible-authenticationform-max-length-regression-in-django-2-1/241

Hints:
Regression test.

Change only the repository files needed to fix the issue.
Do not apply the provided gold patch or edit tests unless the fix requires updating existing tests.