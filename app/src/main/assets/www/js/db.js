/**
 * AuraLeaf Database Module (IndexedDB)
 * Persists PDF files, covers, titles, and reading progress locally & offline.
 */
class BookDB {
  constructor() {
    this.dbName = 'AuraLeafDB';
    this.version = 1;
    this.db = null;
  }

  async init() {
    if (this.db) return this.db;
    return new Promise((resolve, reject) => {
      const request = indexedDB.open(this.dbName, this.version);

      request.onupgradeneeded = (e) => {
        const db = e.target.result;
        if (!db.objectStoreNames.contains('books')) {
          const store = db.createObjectStore('books', { keyPath: 'id' });
          store.createIndex('updatedAt', 'updatedAt', { unique: false });
        }
      };

      request.onsuccess = (e) => {
        this.db = e.target.result;
        resolve(this.db);
      };

      request.onerror = (e) => {
        console.error('IndexedDB Error:', e);
        reject(e);
      };
    });
  }

  async getAllBooks() {
    await this.init();
    return new Promise((resolve, reject) => {
      const tx = this.db.transaction('books', 'readonly');
      const store = tx.objectStore('books');
      const request = store.getAll();

      request.onsuccess = () => {
        const books = request.result || [];
        // Sort by last read / updated descending
        books.sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0));
        resolve(books);
      };

      request.onerror = (e) => reject(e);
    });
  }

  async getBook(id) {
    await this.init();
    return new Promise((resolve, reject) => {
      const tx = this.db.transaction('books', 'readonly');
      const store = tx.objectStore('books');
      const request = store.get(id);

      request.onsuccess = () => resolve(request.result);
      request.onerror = (e) => reject(e);
    });
  }

  async saveBook(book) {
    await this.init();
    return new Promise((resolve, reject) => {
      const tx = this.db.transaction('books', 'readwrite');
      const store = tx.objectStore('books');
      book.updatedAt = Date.now();
      const request = store.put(book);

      request.onsuccess = () => resolve(book.id);
      request.onerror = (e) => reject(e);
    });
  }

  async updateProgress(id, lastPage) {
    await this.init();
    return new Promise((resolve, reject) => {
      const tx = this.db.transaction('books', 'readwrite');
      const store = tx.objectStore('books');
      const getReq = store.get(id);

      getReq.onsuccess = () => {
        const book = getReq.result;
        if (book) {
          book.lastPage = lastPage;
          book.updatedAt = Date.now();
          store.put(book);
        }
        resolve();
      };

      getReq.onerror = (e) => reject(e);
    });
  }

  async renameBook(id, newTitle) {
    await this.init();
    return new Promise((resolve, reject) => {
      const tx = this.db.transaction('books', 'readwrite');
      const store = tx.objectStore('books');
      const getReq = store.get(id);

      getReq.onsuccess = () => {
        const book = getReq.result;
        if (book) {
          book.title = newTitle;
          book.updatedAt = Date.now();
          store.put(book);
        }
        resolve();
      };

      getReq.onerror = (e) => reject(e);
    });
  }

  async deleteBook(id) {
    await this.init();
    return new Promise((resolve, reject) => {
      const tx = this.db.transaction('books', 'readwrite');
      const store = tx.objectStore('books');
      const request = store.delete(id);

      request.onsuccess = () => resolve();
      request.onerror = (e) => reject(e);
    });
  }
}

window.bookDB = new BookDB();
