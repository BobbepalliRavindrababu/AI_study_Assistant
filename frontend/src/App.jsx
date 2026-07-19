import React, { useState, useEffect, useRef } from 'react';
import { 
  BookOpen, 
  Upload, 
  MessageSquare, 
  Send, 
  FileText, 
  File, 
  AlertCircle, 
  CheckCircle,
  HelpCircle,
  Sparkles,
  RefreshCw,
  Search,
  BookMarked
} from 'lucide-react';
import * as pdfjs from 'pdfjs-dist';

// Configure PDF.js Worker
const PDFJS_VERSION = '4.3.136'; // Standard version
pdfjs.GlobalWorkerOptions.workerSrc = `https://cdnjs.cloudflare.com/ajax/libs/pdf.js/${pdfjs.version || PDFJS_VERSION}/pdf.worker.min.mjs`;

const BACKEND_URL = 'http://localhost:8085';

function App() {
  const [documents, setDocuments] = useState([]);
  const [activeDocId, setActiveDocId] = useState(null);
  const [messages, setMessages] = useState([]);
  const [inputQuery, setInputQuery] = useState('');
  const [activeTab, setActiveTab] = useState('summary');
  const [summaryText, setSummaryText] = useState('');
  const [clarifyText, setClarifyText] = useState('');
  const [conceptInput, setConceptInput] = useState('');
  const [hoveredCitation, setHoveredCitation] = useState(null);

  // Loadings
  const [apiOnline, setApiOnline] = useState(false);
  const [apiDetails, setApiDetails] = useState(null);
  const [uploading, setUploading] = useState(false);
  const [chatLoading, setChatLoading] = useState(false);
  const [summaryLoading, setSummaryLoading] = useState(false);
  const [clarifyLoading, setClarifyLoading] = useState(false);

  // Notification Toast
  const [toast, setToast] = useState(null);
  
  const chatEndRef = useRef(null);
  const fileInputRef = useRef(null);

  // Check Backend Status & Load Documents
  const checkStatusAndLoad = async () => {
    try {
      const res = await fetch(`${BACKEND_URL}/api/status`);
      if (res.ok) {
        const data = await res.json();
        setApiOnline(true);
        setApiDetails(data);
        
        // Load documents
        const docRes = await fetch(`${BACKEND_URL}/api/documents`);
        if (docRes.ok) {
          const docs = await docRes.json();
          setDocuments(docs);
        }
      } else {
        setApiOnline(false);
      }
    } catch (e) {
      setApiOnline(false);
    }
  };

  useEffect(() => {
    checkStatusAndLoad();
    // Poll status every 15 seconds
    const interval = setInterval(checkStatusAndLoad, 15000);
    return () => clearInterval(interval);
  }, []);

  // Scroll to bottom of chat
  useEffect(() => {
    chatEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages, chatLoading]);

  const showToast = (message, type = 'info') => {
    setToast({ message, type });
    setTimeout(() => setToast(null), 4000);
  };

  // Extract text from text/markdown files
  const readTextFile = (file) => {
    return new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = (e) => resolve(e.target.result);
      reader.onerror = (e) => reject(e);
      reader.readAsText(file);
    });
  };

  // Extract text from PDF using PDF.js browser API
  const readPdfFile = async (file) => {
    const arrayBuffer = await file.arrayBuffer();
    const loadingTask = pdfjs.getDocument({ data: arrayBuffer });
    const pdf = await loadingTask.promise;
    let extractedText = '';

    for (let i = 1; i <= pdf.numPages; i++) {
      const page = await pdf.getPage(i);
      const textContent = await page.getTextContent();
      const pageText = textContent.items.map(item => item.str).join(' ');
      extractedText += pageText + '\n';
    }

    return extractedText;
  };

  // Handle Drag & Drop Upload
  const handleFileUpload = async (event) => {
    const file = event.target.files?.[0];
    if (!file) return;

    // Validate type
    const isPDF = file.type === 'application/pdf' || file.name.endsWith('.pdf');
    const isTxt = file.type === 'text/plain' || file.name.endsWith('.txt') || file.name.endsWith('.md');

    if (!isPDF && !isTxt) {
      showToast('Unsupported file type. Please upload a PDF, TXT or MD document.', 'error');
      return;
    }

    setUploading(true);
    showToast(`Parsing ${file.name} in browser...`);

    try {
      let contentText = '';
      if (isPDF) {
        contentText = await readPdfFile(file);
      } else {
        contentText = await readTextFile(file);
      }

      if (!contentText || contentText.trim().length === 0) {
        throw new Error('Extracted document text is empty.');
      }

      showToast('Uploading parsed text to backend...');

      const ingestRes = await fetch(`${BACKEND_URL}/api/ingest`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          name: file.name,
          content: contentText
        })
      });

      if (!ingestRes.ok) {
        const errData = await ingestRes.json();
        throw new Error(errData.error || 'Ingest request failed');
      }

      const docData = await ingestRes.json();
      showToast(`${file.name} uploaded successfully!`, 'success');
      
      // Refresh documents
      await checkStatusAndLoad();
      setActiveDocId(docData.id);
    } catch (err) {
      console.error(err);
      showToast(`Upload failed: ${err.message}`, 'error');
    } finally {
      setUploading(false);
      if (fileInputRef.current) {
        fileInputRef.current.value = '';
      }
    }
  };

  // Send Query to Backend
  const handleSendQuery = async (e, forcedQuery = null) => {
    if (e) e.preventDefault();
    const query = forcedQuery || inputQuery;
    if (!query || query.trim().length === 0) return;

    if (!apiOnline) {
      showToast('Backend is offline. Please verify the server is running.', 'error');
      return;
    }

    // Append User Message
    const userMsg = {
      id: Date.now() + '-user',
      sender: 'user',
      text: query
    };
    setMessages(prev => [...prev, userMsg]);
    setInputQuery('');
    setChatLoading(true);

    try {
      const res = await fetch(`${BACKEND_URL}/api/chat`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          query: query,
          docId: activeDocId
        })
      });

      if (!res.ok) {
        throw new Error('Chat query failed.');
      }

      const data = await res.json();
      const assistantMsg = {
        id: Date.now() + '-assistant',
        sender: 'assistant',
        text: data.answer,
        chunks: data.chunks || []
      };

      setMessages(prev => [...prev, assistantMsg]);
    } catch (err) {
      showToast(err.message, 'error');
      setMessages(prev => [...prev, {
        id: Date.now() + '-assistant-err',
        sender: 'assistant',
        text: 'Sorry, I encountered an error connecting to the AI backend. Please verify your API Key.'
      }]);
    } finally {
      setChatLoading(false);
    }
  };

  // Summarise Active Document
  const handleSummarise = async () => {
    if (!activeDocId) {
      showToast('Please select or upload a document to summarise.', 'error');
      return;
    }

    setSummaryLoading(true);
    setSummaryText('');

    try {
      const res = await fetch(`${BACKEND_URL}/api/summarise`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ docId: activeDocId })
      });

      if (!res.ok) {
        throw new Error('Failed to generate summary.');
      }

      const data = await res.json();
      setSummaryText(data.summary);
    } catch (err) {
      showToast(err.message, 'error');
      setSummaryText('Error generating summary. Please check backend server log.');
    } finally {
      setSummaryLoading(false);
    }
  };

  // Clarify Selected Concept
  const handleClarify = async (e, conceptName = null) => {
    if (e) e.preventDefault();
    const concept = conceptName || conceptInput;
    if (!concept || concept.trim().length === 0) return;

    setActiveTab('clarify');
    setClarifyLoading(true);
    setClarifyText('');

    // Fetch context from active document if available
    let docContext = '';
    if (activeDocId) {
      const activeDoc = documents.find(d => d.id === activeDocId);
      if (activeDoc) {
        docContext = `Based on the document named: ${activeDoc.name}`;
      }
    }

    try {
      const res = await fetch(`${BACKEND_URL}/api/clarify`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          concept: concept,
          context: docContext
        })
      });

      if (!res.ok) {
        throw new Error('Clarification request failed.');
      }

      const data = await res.json();
      setClarifyText(data.explanation);
      setConceptInput(concept);
    } catch (err) {
      showToast(err.message, 'error');
      setClarifyText('Error clarifying concept.');
    } finally {
      setClarifyLoading(false);
    }
  };

  const getActiveDocName = () => {
    const doc = documents.find(d => d.id === activeDocId);
    return doc ? doc.name : 'All Documents';
  };

  // Clean Markdown response for simple display
  const formatMarkdown = (text) => {
    if (!text) return '';
    // Basic formatting lines
    return text.split('\n').map((line, idx) => {
      if (line.startsWith('## ')) {
        return <h2 key={idx}>{line.replace('## ', '')}</h2>;
      }
      if (line.startsWith('### ')) {
        return <h3 key={idx}>{line.replace('### ', '')}</h3>;
      }
      if (line.startsWith('- ') || line.startsWith('* ')) {
        return <li key={idx} style={{ marginLeft: '16px', listStyleType: 'disc' }}>{line.substring(2)}</li>;
      }
      if (line.match(/^\d+\.\s/)) {
        return <li key={idx} style={{ marginLeft: '16px', listStyleType: 'decimal' }}>{line.replace(/^\d+\.\s/, '')}</li>;
      }
      // Look for codeblock
      if (line.startsWith('```')) {
        return null; // hide raw ticks
      }
      return <p key={idx}>{line}</p>;
    });
  };

  return (
    <div className="app-container">
      {/* 1. Sidebar */}
      <div className="sidebar">
        <div className="logo-section">
          <BookOpen className="logo-icon" size={24} />
          <span className="logo-text">AG StudyRAG</span>
        </div>

        <div className="upload-section">
          <input 
            type="file" 
            ref={fileInputRef} 
            onChange={handleFileUpload} 
            style={{ display: 'none' }} 
            accept=".pdf,.txt,.md"
          />
          <div 
            className="file-dropzone"
            onClick={() => fileInputRef.current?.click()}
          >
            <Upload className="file-dropzone-icon" size={28} />
            <span style={{ fontSize: '0.85rem', fontWeight: 600 }}>Upload Material</span>
            <span style={{ fontSize: '0.7rem', color: 'var(--text-muted)' }}>PDF, TXT, or MD (Max 15MB)</span>
          </div>
        </div>

        <div className="document-list-container">
          <h3 className="section-title">Study Library</h3>
          
          <div 
            className={`doc-item ${activeDocId === null ? 'active' : ''}`}
            onClick={() => setActiveDocId(null)}
          >
            <div className="doc-info">
              <BookMarked size={16} style={{ color: activeDocId === null ? 'var(--primary)' : 'var(--text-muted)' }} />
              <span className="doc-name">All Ingested Data</span>
            </div>
          </div>

          {documents.length === 0 ? (
            <div style={{ padding: '20px 0', textAlign: 'center', fontSize: '0.8rem', color: 'var(--text-dim)' }}>
              No study materials uploaded yet.
            </div>
          ) : (
            documents.map(doc => (
              <div 
                key={doc.id} 
                className={`doc-item ${activeDocId === doc.id ? 'active' : ''}`}
                onClick={() => setActiveDocId(doc.id)}
              >
                <div className="doc-info">
                  <FileText size={16} style={{ color: activeDocId === doc.id ? 'var(--primary)' : 'var(--text-muted)' }} />
                  <div style={{ display: 'flex', flexDirection: 'column' }}>
                    <span className="doc-name">{doc.name}</span>
                    <span className="doc-chunks">{doc.chunkCount} segments loaded</span>
                  </div>
                </div>
              </div>
            ))
          )}
        </div>

        <div className="sidebar-footer">
          <div className="status-indicator">
            <div className={`status-dot ${apiOnline ? 'online' : ''}`}></div>
            <span>{apiOnline ? 'RAG Engine Online' : 'RAG Engine Offline'}</span>
          </div>
          <button 
            onClick={checkStatusAndLoad}
            style={{ background: 'none', border: 'none', color: 'var(--text-dim)', cursor: 'pointer' }}
            title="Refresh connection"
          >
            <RefreshCw size={14} />
          </button>
        </div>
      </div>

      {/* 2. Main Chat RAG Interface */}
      <div className="main-chat-container">
        <div className="chat-header">
          <div className="chat-header-title">
            <Sparkles size={18} style={{ color: 'var(--primary)' }} />
            <span>AI Concept Coach</span>
            <span style={{ fontSize: '0.75rem', color: 'var(--text-muted)', background: 'var(--bg-surface-elevated)', padding: '2px 8px', borderRadius: '10px' }}>
              Scope: {getActiveDocName()}
            </span>
          </div>
          {apiDetails && !apiDetails.apiKeyConfigured && (
            <div style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: '0.75rem', color: 'var(--warning)', background: 'rgba(234, 179, 8, 0.1)', padding: '4px 10px', borderRadius: '6px', border: '1px solid rgba(234, 179, 8, 0.2)' }}>
              <AlertCircle size={12} />
              <span>Warning: API key not set in backend</span>
            </div>
          )}
        </div>

        <div className="chat-messages">
          {messages.length === 0 ? (
            <div className="welcome-screen">
              <Sparkles size={48} style={{ color: 'var(--primary)', filter: 'drop-shadow(0 0 10px var(--primary-glow))' }} />
              <h2 className="welcome-title">Learn faster with AI RAG</h2>
              <p className="welcome-description">
                Upload your textbook PDFs, lecture notes, or markdown syllabus, and chat directly with them. 
                Our Retrieval-Augmented Generation (RAG) system extracts exact paragraphs to answer your queries with source citations.
              </p>
              
              <div className="welcome-cards">
                <div 
                  className="welcome-card"
                  onClick={() => handleSendQuery(null, "Give me an overview of the key concepts in this material.")}
                >
                  <h4>📚 Concept Overview</h4>
                  <p>Asks the assistant to compile a summary of the main points.</p>
                </div>
                <div 
                  className="welcome-card"
                  onClick={() => {
                    setActiveTab('clarify');
                    setConceptInput('Retrieval-Augmented Generation');
                    showToast('Press Clarify on the right panel!');
                  }}
                >
                  <h4>💡 Clarify Terminology</h4>
                  <p>Deep-dive explanations of core keywords with real-world analogies.</p>
                </div>
              </div>
            </div>
          ) : (
            messages.map(msg => (
              <div key={msg.id} className={`message ${msg.sender}`}>
                <div className="avatar">
                  {msg.sender === 'user' ? 'U' : 'AI'}
                </div>
                <div className="message-bubble">
                  {msg.text}

                  {/* Render citations if present */}
                  {msg.chunks && msg.chunks.length > 0 && (
                    <div className="citation-list">
                      {msg.chunks.map((chunk, idx) => (
                        <div 
                          key={chunk.id} 
                          className="citation-badge"
                          onMouseEnter={() => setHoveredCitation(chunk)}
                          onMouseLeave={() => setHoveredCitation(null)}
                          onClick={() => {
                            setActiveTab('clarify');
                            handleClarify(null, chunk.content.substring(0, 40));
                          }}
                        >
                          <File size={10} />
                          <span>[{chunk.docName} Segment {chunk.index + 1}]</span>
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              </div>
            ))
          )}
          {chatLoading && (
            <div className="message assistant">
              <div className="avatar">AI</div>
              <div className="message-bubble" style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                <div className="loading-spinner"></div>
                <span>Retrieving context & generating answer...</span>
              </div>
            </div>
          )}
          <div ref={chatEndRef} />
        </div>

        {/* Hover citation detail tooltip */}
        {hoveredCitation && (
          <div style={{
            position: 'absolute',
            bottom: '90px',
            left: '300px',
            right: '360px',
            background: 'var(--bg-surface-elevated)',
            border: '1px solid var(--primary)',
            padding: '12px',
            borderRadius: '8px',
            boxShadow: '0 4px 20px rgba(0,0,0,0.5)',
            fontSize: '0.8rem',
            zIndex: 999
          }}>
            <strong>Retrieved Context (Similarity Match):</strong>
            <p style={{ marginTop: '4px', fontStyle: 'italic', color: 'var(--text-muted)' }}>
              "...{hoveredCitation.content}..."
            </p>
          </div>
        )}

        <div className="chat-input-area">
          <form className="chat-form" onSubmit={handleSendQuery}>
            <input 
              type="text" 
              className="chat-input"
              value={inputQuery}
              onChange={e => setInputQuery(e.target.value)}
              placeholder={documents.length === 0 ? "Upload materials on the left to activate RAG chat..." : "Ask questions about your study materials..."}
              disabled={chatLoading}
            />
            <button 
              type="submit" 
              className="send-btn"
              disabled={chatLoading || !inputQuery.trim()}
            >
              <Send size={18} />
            </button>
          </form>
        </div>
      </div>

      {/* 3. Right Detail Panel */}
      <div className="detail-column">
        <div className="tab-nav">
          <button 
            className={`tab-btn ${activeTab === 'summary' ? 'active' : ''}`}
            onClick={() => setActiveTab('summary')}
          >
            Syllabus Summariser
          </button>
          <button 
            className={`tab-btn ${activeTab === 'clarify' ? 'active' : ''}`}
            onClick={() => setActiveTab('clarify')}
          >
            Concept Clarifier
          </button>
        </div>

        <div className="tab-content">
          {/* A. Summary Tab */}
          {activeTab === 'summary' && (
            <div className="summary-card">
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                <h3 style={{ fontSize: '0.95rem', fontWeight: 600 }}>Active Material Summary</h3>
                {activeDocId && (
                  <button 
                    className="generate-btn"
                    onClick={handleSummarise}
                    disabled={summaryLoading}
                    style={{ padding: '6px 12px', fontSize: '0.75rem' }}
                  >
                    {summaryLoading ? 'Summarising...' : 'Regenerate'}
                  </button>
                )}
              </div>

              {!activeDocId ? (
                <div className="empty-state">
                  <FileText className="empty-state-icon" size={36} />
                  <p style={{ fontSize: '0.8rem' }}>Select a document on the left sidebar to generate a structured core summary.</p>
                </div>
              ) : summaryLoading ? (
                <div className="empty-state">
                  <div className="loading-spinner" style={{ width: '28px', height: '28px', borderWidth: '3px' }}></div>
                  <p style={{ fontSize: '0.8rem', marginTop: '12px' }}>Synthesising study notes with Gemini AI...</p>
                </div>
              ) : summaryText ? (
                <div className="markdown-content">
                  {formatMarkdown(summaryText)}
                </div>
              ) : (
                <div className="empty-state">
                  <Sparkles className="empty-state-icon" size={32} style={{ color: 'var(--primary)' }} />
                  <p style={{ fontSize: '0.85rem' }}>No summary generated yet.</p>
                  <button 
                    className="generate-btn" 
                    onClick={handleSummarise}
                    style={{ marginTop: '10px' }}
                  >
                    Generate Study Summary
                  </button>
                </div>
              )}
            </div>
          )}

          {/* B. Clarify Tab */}
          {activeTab === 'clarify' && (
            <div className="clarify-card">
              <form onSubmit={handleClarify} style={{ display: 'flex', gap: '8px' }}>
                <input 
                  type="text" 
                  className="chat-input"
                  style={{ padding: '8px 12px', fontSize: '0.85rem' }}
                  placeholder="Enter complex concept (e.g. Cosine Similarity)..."
                  value={conceptInput}
                  onChange={e => setConceptInput(e.target.value)}
                  disabled={clarifyLoading}
                />
                <button 
                  type="submit" 
                  className="send-btn" 
                  style={{ padding: '0 12px', borderRadius: '8px' }}
                  disabled={clarifyLoading || !conceptInput.trim()}
                >
                  <Search size={14} />
                </button>
              </form>

              {clarifyLoading ? (
                <div className="empty-state">
                  <div className="loading-spinner" style={{ width: '28px', height: '28px', borderWidth: '3px' }}></div>
                  <p style={{ fontSize: '0.8rem', marginTop: '12px' }}>Building detailed clarification mind map...</p>
                </div>
              ) : clarifyText ? (
                <div className="markdown-content">
                  {formatMarkdown(clarifyText)}
                </div>
              ) : (
                <div className="empty-state">
                  <HelpCircle className="empty-state-icon" size={36} />
                  <p style={{ fontSize: '0.8rem' }}>Enter any terminology above or click a RAG citation bubble to clarify concepts with real-world analogies.</p>
                </div>
              )}
            </div>
          )}
        </div>
      </div>

      {/* 4. Upload Progress Overlay */}
      {uploading && (
        <div style={{
          position: 'fixed',
          top: 0,
          left: 0,
          width: '100vw',
          height: '100vh',
          background: 'rgba(5, 6, 12, 0.85)',
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          justifyContent: 'center',
          zIndex: 10000,
          gap: '16px'
        }}>
          <div className="loading-spinner" style={{ width: '48px', height: '48px', borderWidth: '4px' }}></div>
          <div style={{ color: 'white', fontWeight: 600 }}>Extracting Document & Ingesting...</div>
          <div style={{ color: 'var(--text-muted)', fontSize: '0.8rem' }}>This happens fully client-side and chunked on your server</div>
        </div>
      )}

      {/* 5. Notification Toast */}
      {toast && (
        <div className="toast" style={{
          borderLeftColor: toast.type === 'error' ? 'var(--danger)' : 
                          toast.type === 'success' ? 'var(--success)' : 'var(--primary)'
        }}>
          {toast.message}
        </div>
      )}
    </div>
  );
}

export default App;
