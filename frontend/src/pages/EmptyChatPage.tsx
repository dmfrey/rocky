import { Link } from "react-router-dom";
import { Icon, RockyMark } from "../components/Icon";

export function EmptyChatPage() {
  return (
    <div className="welcome-page">
      <div className="welcome-mark">
        <RockyMark />
      </div>
      <p className="eyebrow">YOUR KNOWLEDGE WORKSPACE</p>
      <h1>Answers grounded in your documents</h1>
      <p className="welcome-description">
        Explore your shared knowledge base with Rocky. Start a new chat or
        return to a conversation from the sidebar.
      </p>
      <div className="welcome-steps">
        <div>
          <Icon name="documents" size={28} />
          <h2>Add your documents</h2>
          <p>Upload reference material to the shared knowledge base.</p>
          <Link to="/documents">
            Manage documents <Icon name="chevron" size={14} />
          </Link>
        </div>
        <div>
          <Icon name="chat" size={28} />
          <h2>Ask a question</h2>
          <p>Use New chat to explore topics and follow up on the answers.</p>
        </div>
        <div>
          <Icon name="check" size={28} />
          <h2>Review the sources</h2>
          <p>Check the cited documents to verify and understand each answer.</p>
        </div>
      </div>
    </div>
  );
}
