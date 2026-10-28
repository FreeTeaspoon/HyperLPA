try {
  const theme = localStorage.getItem('hyperlpa-theme');
  if (theme === 'light' || theme === 'dark') document.documentElement.dataset.theme = theme;
} catch {}
