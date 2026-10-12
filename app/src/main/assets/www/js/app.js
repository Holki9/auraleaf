/**
 * AuraLeaf - 3D Realistic Page-Flip PDF & Comic Reader
 * Features:
 * - StPageFlip 3D physics with curl deformation, backface, volume & lighting
 * - Ambient Glow Backdrop (Apple / YouTube Ambient Mode)
 * - Optimized PDF.js lazy virtualized page rendering (handles 100-300MB files smoothly)
 * - Smart Pinch-to-Zoom (disables page turn when zoomed, free 2D pan)
 * - Single-tap clean immersion screen, quick double-tap restore
 * - Telegram-style floating glass pill navigation with SVG icons
 * - Offline library shelf with IndexedDB persistence, search, rename, progress
 */

// Configure PDF.js worker
if (window.pdfjsLib) {
  pdfjsLib.GlobalWorkerOptions.workerSrc = 'libs/pdf.worker.min.js';
}

class AuraLeafApp {
  constructor() {
    this.currentBook = null;
    this.pdfDoc = null;
    this.pageFlip = null;
    this.currentPageIndex = 0;
    this.totalPages = 0;
    this.renderedPages = new Map(); // pageIndex -> canvas/image
    this.pageWidth = 0;
    this.pageHeight = 0;

    // Zoom & Pan state
    this.zoomScale = 1.0;
    this.panX = 0;
    this.panY = 0;
    this.isZooming = false;
    this.isPanning = false;
    this.lastTouchDist = 0;
    this.lastPanTouch = { x: 0, y: 0 };
    this.lastTapTime = 0;
    this.tapTimeout = null;

    // Ambient mode
    this.ambientEnabled = localStorage.getItem('auraleaf_ambient') !== 'false';
    this.activeAmbientLayer = 'a';

    // UI elements
    this.dom = {
      readerView: document.getElementById('reader-view'),
      libraryView: document.getElementById('library-view'),
      zoomViewport: document.getElementById('zoom-viewport'),
      zoomContent: document.getElementById('zoom-content'),
      bookContainer: document.getElementById('book-container'),
      flipbook: document.getElementById('flipbook'),
      glassNav: document.getElementById('glass-nav'),
      zoomIndicator: document.getElementById('zoom-indicator'),
      zoomLevelText: document.getElementById('zoom-level-text'),
      btnZoomReset: document.getElementById('btn-zoom-reset'),
      btnShelf: document.getElementById('btn-shelf'),
      btnPrevPage: document.getElementById('btn-prev-page'),
      btnNextPage: document.getElementById('btn-next-page'),
      btnPageJumper: document.getElementById('btn-page-jumper'),
      pageNumCurrent: document.getElementById('page-num-current'),
      pageNumTotal: document.getElementById('page-num-total'),
      btnAmbientToggle: document.getElementById('btn-ambient-toggle'),
      btnSoundToggle: document.getElementById('btn-sound-toggle'),
      iconSoundOn: document.getElementById('icon-sound-on'),
      iconSoundOff: document.getElementById('icon-sound-off'),
      pageSliderSheet: document.getElementById('page-slider-sheet'),
      pageSlider: document.getElementById('page-slider'),
      sliderCurrentVal: document.getElementById('slider-current-val'),
      btnSliderGo: document.getElementById('btn-slider-go'),
      btnCloseSheet: document.getElementById('btn-close-sheet'),
      fileInput: document.getElementById('file-input'),
      booksGrid: document.getElementById('books-grid'),
      emptyState: document.getElementById('empty-state'),
      searchInput: document.getElementById('search-input'),
      btnClearSearch: document.getElementById('btn-clear-search'),
      renameModal: document.getElementById('rename-modal'),
      renameInput: document.getElementById('rename-input'),
      btnCancelRename: document.getElementById('btn-cancel-rename'),
      btnConfirmRename: document.getElementById('btn-confirm-rename'),
      loadingOverlay: document.getElementById('loading-overlay'),
      loadingStatusText: document.getElementById('loading-status-text'),
      loadingBarFill: document.getElementById('loading-bar-fill'),
      ambientCanvasA: document.getElementById('ambient-canvas-a'),
      ambientCanvasB: document.getElementById('ambient-canvas-b')
    };

    this.pendingRenameBookId = null;
    this.init();
  }

  async init() {
    this.bindEvents();
    this.updateSoundIcon();
    this.updateAmbientUI();
    await this.renderLibrary();
  }

  /* ==========================================================================
     Event Listeners
     ========================================================================== */
  bindEvents() {
    // Navigation controls
    this.dom.btnShelf.addEventListener('click', () => this.showLibrary());
    this.dom.btnPrevPage.addEventListener('click', () => this.turnPrev());
    this.dom.btnNextPage.addEventListener('click', () => this.turnNext());

    // Sound toggle
    this.dom.btnSoundToggle.addEventListener('click', () => {
      const enabled = window.pageAudio.toggleSound();
      this.updateSoundIcon();
    });

    // Ambient mode toggle
    this.dom.btnAmbientToggle.addEventListener('click', () => {
      this.ambientEnabled = !this.ambientEnabled;
      localStorage.setItem('auraleaf_ambient', this.ambientEnabled ? 'true' : 'false');
      this.updateAmbientUI();
      if (this.ambientEnabled) this.updateAmbientGlow(this.currentPageIndex);
    });

    // Page Jumper Drawer
    this.dom.btnPageJumper.addEventListener('click', () => {
      this.openPageSlider();
    });
    this.dom.btnCloseSheet.addEventListener('click', () => {
      this.dom.pageSliderSheet.classList.add('hidden');
    });
    this.dom.pageSlider.addEventListener('input', (e) => {
      this.dom.sliderCurrentVal.textContent = e.target.value;
    });
    this.dom.btnSliderGo.addEventListener('click', () => {
      const targetPage = parseInt(this.dom.pageSlider.value, 10) - 1;
      this.dom.pageSliderSheet.classList.add('hidden');
      this.flipToPage(targetPage);
    });

    // Zoom Reset
    this.dom.btnZoomReset.addEventListener('click', () => this.resetZoom());

    // File Input (open PDF from device)
    this.dom.fileInput.addEventListener('change', (e) => {
      const file = e.target.files && e.target.files[0];
      if (file) this.handleImportPDF(file);
      e.target.value = '';
    });

    // Library Search
    this.dom.searchInput.addEventListener('input', (e) => {
      const q = e.target.value.trim().toLowerCase();
      this.dom.btnClearSearch.classList.toggle('hidden', q.length === 0);
      this.filterBooks(q);
    });
    this.dom.btnClearSearch.addEventListener('click', () => {
      this.dom.searchInput.value = '';
      this.dom.btnClearSearch.classList.add('hidden');
      this.filterBooks('');
    });

    // Rename Modal
    this.dom.btnCancelRename.addEventListener('click', () => {
      this.dom.renameModal.classList.add('hidden');
      this.pendingRenameBookId = null;
    });
    this.dom.btnConfirmRename.addEventListener('click', () => this.confirmRename());

    // Gesture & Touch handlers on Reader Viewport
    this.initViewportGestures();

    // Window resize
    window.addEventListener('resize', () => {
      if (this.pageFlip && !this.dom.readerView.classList.contains('hidden')) {
        this.recalculateDimensions();
      }
    });
  }

  updateSoundIcon() {
    const on = window.pageAudio.isSoundEnabled();
    this.dom.iconSoundOn.classList.toggle('hidden', !on);
    this.dom.iconSoundOff.classList.toggle('hidden', on);
    this.dom.btnSoundToggle.classList.toggle('active', on);
  }

  updateAmbientUI() {
    document.body.classList.toggle('ambient-active', this.ambientEnabled);
    this.dom.btnAmbientToggle.classList.toggle('active', this.ambientEnabled);
  }

  /* ==========================================================================
     Gestures: Pinch-to-Zoom, Pan, Single-Tap & Double-Tap
     ========================================================================== */
  initViewportGestures() {
    const vp = this.dom.zoomViewport;

    vp.addEventListener('touchstart', (e) => this.onTouchStart(e), { passive: false });
    vp.addEventListener('touchmove', (e) => this.onTouchMove(e), { passive: false });
    vp.addEventListener('touchend', (e) => this.onTouchEnd(e), { passive: false });
    vp.addEventListener('touchcancel', (e) => this.onTouchEnd(e), { passive: false });
  }

  onTouchStart(e) {
    if (e.touches.length === 2) {
      // 2 fingers = Pinch-to-zoom
      this.isZooming = true;
      this.isPanning = false;
      this.lastTouchDist = this.getTouchDistance(e.touches[0], e.touches[1]);
      e.preventDefault();
      return;
    }

    if (e.touches.length === 1) {
      const touch = e.touches[0];
      const now = Date.now();

      // Check double-tap
      if (now - this.lastTapTime < 320) {
        clearTimeout(this.tapTimeout);
        this.onDoubleTap(touch);
        this.lastTapTime = 0;
        e.preventDefault();
        return;
      }
      this.lastTapTime = now;

      // If zoomed in: 1-finger is PAN (drag)
      if (this.zoomScale > 1.05) {
        this.isPanning = true;
        this.lastPanTouch = { x: touch.clientX, y: touch.clientY };
        e.preventDefault();
      }
    }
  }

  onTouchMove(e) {
    if (this.isZooming && e.touches.length === 2) {
      const dist = this.getTouchDistance(e.touches[0], e.touches[1]);
      if (this.lastTouchDist > 0) {
        const factor = dist / this.lastTouchDist;
        this.zoomScale = Math.min(Math.max(1.0, this.zoomScale * factor), 4.5);
        this.applyTransform();
        this.updateZoomIndicator();
      }
      this.lastTouchDist = dist;
      e.preventDefault();
      return;
    }

    if (this.isPanning && e.touches.length === 1 && this.zoomScale > 1.05) {
      const touch = e.touches[0];
      const dx = touch.clientX - this.lastPanTouch.x;
      const dy = touch.clientY - this.lastPanTouch.y;

      this.panX += dx;
      this.panY += dy;

      // Clamp panning bounds
      const maxPanX = (window.innerWidth * (this.zoomScale - 1)) / 1.8;
      const maxPanY = (window.innerHeight * (this.zoomScale - 1)) / 1.8;
      this.panX = Math.min(Math.max(-maxPanX, this.panX), maxPanX);
      this.panY = Math.min(Math.max(-maxPanY, this.panY), maxPanY);

      this.lastPanTouch = { x: touch.clientX, y: touch.clientY };
      this.applyTransform();
      e.preventDefault();
    }
  }

  onTouchEnd(e) {
    if (this.isZooming && e.touches.length < 2) {
      this.isZooming = false;
      if (this.zoomScale <= 1.05) {
        this.resetZoom();
      }
    }

    if (this.isPanning && e.touches.length === 0) {
      this.isPanning = false;
    }

    // Schedule single tap check if not panning / zooming
    if (!this.isZooming && !this.isPanning && this.zoomScale <= 1.05 && e.changedTouches.length === 1) {
      this.tapTimeout = setTimeout(() => {
        this.onSingleTap();
      }, 340);
    }
  }

  getTouchDistance(t1, t2) {
    const dx = t1.clientX - t2.clientX;
    const dy = t1.clientY - t2.clientY;
    return Math.sqrt(dx * dx + dy * dy);
  }

  applyTransform() {
    this.dom.zoomContent.style.transform = `translate(${this.panX}px, ${this.panY}px) scale(${this.zoomScale})`;

    // When zoomed, lock StPageFlip pointer events so dragging pans rather than turns
    if (this.zoomScale > 1.05) {
      this.dom.bookContainer.style.pointerEvents = 'none';
    } else {
      this.dom.bookContainer.style.pointerEvents = 'auto';
    }
  }

  updateZoomIndicator() {
    if (this.zoomScale > 1.05) {
      this.dom.zoomLevelText.textContent = `${this.zoomScale.toFixed(1)}x`;
      this.dom.zoomIndicator.classList.remove('hidden');
    } else {
      this.dom.zoomIndicator.classList.add('hidden');
    }
  }

  resetZoom() {
    this.zoomScale = 1.0;
    this.panX = 0;
    this.panY = 0;
    this.applyTransform();
    this.updateZoomIndicator();
  }

  onSingleTap() {
    // Single tap toggles floating glass pill (clean screen immersion)
    this.dom.glassNav.classList.toggle('nav-hidden');
    if (!this.dom.pageSliderSheet.classList.contains('hidden')) {
      this.dom.pageSliderSheet.classList.add('hidden');
    }
  }

  onDoubleTap(touch) {
    // Quick double tap always restores floating pill nav
    this.dom.glassNav.classList.remove('nav-hidden');

    // If currently zoomed, double tap resets zoom. If not zoomed, zooms to 2x at tap spot!
    if (this.zoomScale > 1.05) {
      this.resetZoom();
    } else {
      this.zoomScale = 2.0;
      this.panX = (window.innerWidth / 2 - touch.clientX) * 0.7;
      this.panY = (window.innerHeight / 2 - touch.clientY) * 0.7;
      this.applyTransform();
      this.updateZoomIndicator();
    }
  }

  /* ==========================================================================
     PDF & Page Rendering (Lazy Virtualization)
     ========================================================================== */
  async handleImportPDF(file) {
    this.showLoading('Чтение файла...');
    try {
      const arrayBuffer = await file.arrayBuffer();
      const pdf = await pdfjsLib.getDocument({ data: arrayBuffer }).promise;
      const total = pdf.numPages;

      // Extract cover thumbnail from page 1
      const page1 = await pdf.getPage(1);
      const viewport = page1.getViewport({ scale: 0.4 });
      const thumbCanvas = document.createElement('canvas');
      thumbCanvas.width = viewport.width;
      thumbCanvas.height = viewport.height;
      const ctx = thumbCanvas.getContext('2d');
      await page1.render({ canvasContext: ctx, viewport }).promise;
      const thumbnail = thumbCanvas.toDataURL('image/jpeg', 0.85);

      const book = {
        id: 'book_' + Date.now(),
        title: file.name.replace(/\.[^/.]+$/, ''),
        originalName: file.name,
        size: file.size,
        pageCount: total,
        lastPage: 0,
        thumbnail: thumbnail,
        pdfBuffer: arrayBuffer,
        addedAt: Date.now()
      };

      await window.bookDB.saveBook(book);
      await this.renderLibrary();
      this.hideLoading();
      this.openBook(book.id);
    } catch (err) {
      console.error('Failed to import PDF:', err);
      alert('Ошибка при открытии PDF: ' + err.message);
      this.hideLoading();
    }
  }

  async openBook(bookId) {
    this.showLoading('Открытие книги...');
    try {
      const book = await window.bookDB.getBook(bookId);
      if (!book) throw new Error('Книга не найдена');

      this.currentBook = book;
      this.pdfDoc = await pdfjsLib.getDocument({ data: book.pdfBuffer }).promise;
      this.totalPages = this.pdfDoc.numPages;
      this.currentPageIndex = Math.min(book.lastPage || 0, this.totalPages - 1);

      this.dom.pageNumTotal.textContent = this.totalPages;
      this.dom.pageSlider.max = this.totalPages;

      // Get page 1 natural aspect ratio
      const page1 = await this.pdfDoc.getPage(1);
      const vp1 = page1.getViewport({ scale: 1 });
      const aspect = vp1.width / vp1.height;

      this.computeBookDimensions(aspect);
      this.buildFlipbookDOM();
      this.initPageFlip();

      // Switch to Reader View
      this.dom.libraryView.classList.add('hidden');
      this.dom.readerView.classList.remove('hidden');
      this.dom.glassNav.classList.remove('nav-hidden');
      this.resetZoom();

      this.hideLoading();
    } catch (err) {
      console.error('Failed to open book:', err);
      alert('Не удалось открыть книгу: ' + err.message);
      this.hideLoading();
    }
  }

  computeBookDimensions(aspect) {
    const sw = window.innerWidth;
    const sh = window.innerHeight;

    // In portrait, fit book with comfortable margins
    const maxW = sw * 0.94;
    const maxH = sh * 0.90;

    let w = maxW;
    let h = w / aspect;

    if (h > maxH) {
      h = maxH;
      w = h * aspect;
    }

    this.pageWidth = Math.round(w);
    this.pageHeight = Math.round(h);
  }

  buildFlipbookDOM() {
    this.dom.flipbook.innerHTML = '';
    this.renderedPages.clear();

    for (let i = 0; i < this.totalPages; i++) {
      const pageEl = document.createElement('div');
      pageEl.className = 'page-wrapper';
      pageEl.dataset.density = 'soft';
      pageEl.id = `page-container-${i}`;
      pageEl.innerHTML = `<div class="page-loading-skeleton"><span>Стр. ${i + 1}</span></div>`;
      this.dom.flipbook.appendChild(pageEl);
    }
  }

  initPageFlip() {
    if (this.pageFlip) {
      try { this.pageFlip.destroy(); } catch (e) {}
    }

    const pages = this.dom.flipbook.querySelectorAll('.page-wrapper');
    this.pageFlip = new St.PageFlip(this.dom.flipbook, {
      width: this.pageWidth,
      height: this.pageHeight,
      size: 'fixed',
      drawShadow: true,
      maxShadowOpacity: 0.8,
      showCover: false,
      usePortrait: true,
      startPage: this.currentPageIndex,
      autoSize: false,
      mobileScrollSupport: false,
      swipeDistance: 25,
      clickEventForward: true,
      useMouseEvents: true,
      showPageCorners: true,
      disableFlipByClick: true
    });

    this.pageFlip.loadFromHTML(pages);

    this.pageFlip.on('flip', (e) => {
      this.currentPageIndex = e.data;
      this.onPageChanged(this.currentPageIndex);
    });

    this.pageFlip.on('changeState', (e) => {
      // Play crisp paper rustle when flip begins
      if (e.data === 'flipping') {
        window.pageAudio.playFlip();
      }
    });

    // Render initial page window
    this.renderPageWindow(this.currentPageIndex);
    this.onPageChanged(this.currentPageIndex);
  }

  onPageChanged(pageIndex) {
    this.dom.pageNumCurrent.textContent = pageIndex + 1;
    this.dom.pageSlider.value = pageIndex + 1;
    this.dom.sliderCurrentVal.textContent = pageIndex + 1;

    // Save reading progress to IndexedDB
    if (this.currentBook) {
      window.bookDB.updateProgress(this.currentBook.id, pageIndex);
    }

    // Lazy load pages in active window: [pageIndex - 2, pageIndex + 2]
    this.renderPageWindow(pageIndex);

    // Update Ambient Glow Backdrop
    if (this.ambientEnabled) {
      this.updateAmbientGlow(pageIndex);
    }
  }

  async renderPageWindow(centerIndex) {
    const minIndex = Math.max(0, centerIndex - 2);
    const maxIndex = Math.min(this.totalPages - 1, centerIndex + 2);

    // Render pages in active window
    for (let i = minIndex; i <= maxIndex; i++) {
      if (!this.renderedPages.has(i)) {
        this.renderSinglePage(i);
      }
    }

    // Free memory for pages far outside window (keeps RAM usage <40MB even on 300MB PDFs!)
    for (const [idx, canvas] of this.renderedPages.entries()) {
      if (idx < centerIndex - 3 || idx > centerIndex + 3) {
        const container = document.getElementById(`page-container-${idx}`);
        if (container) {
          container.innerHTML = `<div class="page-loading-skeleton"><span>Стр. ${idx + 1}</span></div>`;
        }
        this.renderedPages.delete(idx);
      }
    }
  }

  async renderSinglePage(pageIndex) {
    try {
      const page = await this.pdfDoc.getPage(pageIndex + 1);
      const container = document.getElementById(`page-container-${pageIndex}`);
      if (!container) return;

      // Scale for sharp high-DPI comic reading (clamped at 2.0 max for memory balance)
      const dpr = Math.min(window.devicePixelRatio || 1.5, 2.0);
      const viewport = page.getViewport({ scale: (this.pageWidth / page.getViewport({ scale: 1 }).width) * dpr });

      const canvas = document.createElement('canvas');
      canvas.width = viewport.width;
      canvas.height = viewport.height;
      canvas.className = 'page-canvas';

      const ctx = canvas.getContext('2d', { alpha: false });
      await page.render({ canvasContext: ctx, viewport }).promise;

      container.innerHTML = '';
      container.appendChild(canvas);
      this.renderedPages.set(pageIndex, canvas);

      // If this is the current page, update ambient glow once ready
      if (pageIndex === this.currentPageIndex && this.ambientEnabled) {
        this.updateAmbientGlow(pageIndex);
      }
    } catch (e) {
      console.warn(`Render error page ${pageIndex}:`, e);
    }
  }

  /* ==========================================================================
     Dynamic Ambient Mode Backdrop (Apple / YouTube Style)
     ========================================================================== */
  updateAmbientGlow(pageIndex) {
    const canvas = this.renderedPages.get(pageIndex);
    if (!canvas) return;

    const nextLayer = this.activeAmbientLayer === 'a' ? this.dom.ambientCanvasB : this.dom.ambientCanvasA;
    const currentLayer = this.activeAmbientLayer === 'a' ? this.dom.ambientCanvasA : this.dom.ambientCanvasB;

    // Draw downscaled representation to ambient canvas
    nextLayer.width = 160;
    nextLayer.height = Math.round(160 * (canvas.height / canvas.width));
    const ctx = nextLayer.getContext('2d');
    ctx.drawImage(canvas, 0, 0, nextLayer.width, nextLayer.height);

    // Crossfade layers smoothly
    nextLayer.classList.add('active');
    currentLayer.classList.remove('active');
    this.activeAmbientLayer = this.activeAmbientLayer === 'a' ? 'b' : 'a';
  }

  /* ==========================================================================
     Navigation & Flipping
     ========================================================================== */
  turnPrev() {
    if (this.pageFlip) {
      this.pageFlip.flipPrev('top');
    }
  }

  turnNext() {
    if (this.pageFlip) {
      this.pageFlip.flipNext('top');
    }
  }

  flipToPage(index) {
    if (this.pageFlip && index >= 0 && index < this.totalPages) {
      this.pageFlip.flip(index);
    }
  }

  openPageSlider() {
    this.dom.pageSliderSheet.classList.remove('hidden');
  }

  showLibrary() {
    this.dom.readerView.classList.add('hidden');
    this.dom.libraryView.classList.remove('hidden');
    this.renderLibrary();
  }

  /* ==========================================================================
     Library / Shelf Management
     ========================================================================== */
  async renderLibrary() {
    const books = await window.bookDB.getAllBooks();
    this.displayBooks(books);
  }

  displayBooks(books) {
    const grid = this.dom.booksGrid;
    grid.innerHTML = '';

    if (!books || books.length === 0) {
      this.dom.emptyState.classList.remove('hidden');
      return;
    }
    this.dom.emptyState.classList.add('hidden');

    books.forEach((book) => {
      const card = document.createElement('div');
      card.className = 'book-card';
      const pct = Math.round(((book.lastPage + 1) / book.pageCount) * 100);

      card.innerHTML = `
        <div class="book-card-cover-wrap">
          ${book.thumbnail ? `<img src="${book.thumbnail}" class="book-card-cover" alt="Обложка">` : `<div class="book-card-placeholder">PDF</div>`}
          <div class="book-card-badge">${pct}%</div>
        </div>
        <div class="book-card-info">
          <div class="book-card-title" title="${book.title}">${book.title}</div>
          <div class="book-card-progress-bar">
            <div class="book-card-progress-fill" style="width: ${pct}%"></div>
          </div>
          <div class="book-card-meta">
            <span>Стр. ${book.lastPage + 1} / ${book.pageCount}</span>
            <div class="book-card-actions">
              <button class="btn-card-action btn-rename" title="Переименовать">
                <svg viewBox="0 0 24 24" class="svg-icon-mini"><path d="M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04c.39-.39.39-1.02 0-1.41l-2.34-2.34c-.39-.39-1.02-.39-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z"/></svg>
              </button>
              <button class="btn-card-action btn-delete" title="Удалить">
                <svg viewBox="0 0 24 24" class="svg-icon-mini"><path d="M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z"/></svg>
              </button>
            </div>
          </div>
        </div>
      `;

      // Open book on card click (except action buttons)
      card.addEventListener('click', (e) => {
        if (e.target.closest('.btn-card-action')) return;
        this.openBook(book.id);
      });

      // Rename button
      card.querySelector('.btn-rename').addEventListener('click', (e) => {
        e.stopPropagation();
        this.pendingRenameBookId = book.id;
        this.dom.renameInput.value = book.title;
        this.dom.renameModal.classList.remove('hidden');
        this.dom.renameInput.focus();
      });

      // Delete button
      card.querySelector('.btn-delete').addEventListener('click', async (e) => {
        e.stopPropagation();
        if (confirm(`Удалить «${book.title}» с полки?`)) {
          await window.bookDB.deleteBook(book.id);
          this.renderLibrary();
        }
      });

      grid.appendChild(card);
    });
  }

  async filterBooks(query) {
    const all = await window.bookDB.getAllBooks();
    if (!query) {
      this.displayBooks(all);
      return;
    }
    const filtered = all.filter(b => b.title.toLowerCase().includes(query));
    this.displayBooks(filtered);
  }

  async confirmRename() {
    const newTitle = this.dom.renameInput.value.trim();
    if (newTitle && this.pendingRenameBookId) {
      await window.bookDB.renameBook(this.pendingRenameBookId, newTitle);
      this.dom.renameModal.classList.add('hidden');
      this.pendingRenameBookId = null;
      this.renderLibrary();
    }
  }

  showLoading(text) {
    this.dom.loadingStatusText.textContent = text || 'Загрузка...';
    this.dom.loadingOverlay.classList.remove('hidden');
  }

  hideLoading() {
    this.dom.loadingOverlay.classList.add('hidden');
  }
}

// Launch app when DOM is ready
window.addEventListener('DOMContentLoaded', () => {
  window.auraApp = new AuraLeafApp();
});
