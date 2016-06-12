package com.chat.webservice;

import com.chat.db.Actions;
import com.chat.db.Transformations;
import com.chat.tools.Tools;
import com.chat.types.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.javalite.activejdbc.LazyList;

import java.io.IOException;
import java.sql.Array;
import java.util.*;

import static com.chat.db.Tables.*;
import static com.chat.db.Transformations.*;

/**
 * Created by tyler on 6/5/16.
 */

@WebSocket
public class ThreadedChatWebSocket {

    private String sender, msg;

    static Set<SessionScope> sessionScopes = new HashSet<>();

    // The comment rows
    static LazyList<CommentThreadedView> comments;

    public ThreadedChatWebSocket() {
        Tools.dbInit();

        comments = fetchComments();

        Tools.dbClose();
    }


    @OnWebSocketConnect
    public void onConnect(Session session) throws Exception {


        Tools.dbInit();

        // Get or create the session scope
        SessionScope ss = setupSessionScope(session);

        // Send them their user info
        session.getRemote().sendString(ss.getUserObj().json());

        // Send all the comments to just them
        // TODO need to send only the comments based on their router IE /discussion/1/tree/4


        session.getRemote().sendString(new Comments(comments).json());

        Set<SessionScope> filteredScopes = SessionScope.constructFilteredScopesFromSessionRequest(sessionScopes, session);

        broadcastMessage(filteredScopes, new Users(SessionScope.getUserObjects(filteredScopes)).json());

        log.info("session scope " + ss.json() + " joined");

        Tools.dbClose();


    }

    @OnWebSocketClose
    public void onClose(Session session, int statusCode, String reason) {

        SessionScope ss = SessionScope.findBySession(sessionScopes, session);
        sessionScopes.remove(ss);

        log.info("session scope " + ss.json()+ " left, " + statusCode + " " + reason);

        // Send the updated users to everyone in the right scope
        Set<SessionScope> filteredScopes = SessionScope.constructFilteredScopesFromSessionRequest(sessionScopes, session);

        broadcastMessage(filteredScopes, new Users(SessionScope.getUserObjects(filteredScopes)).json());

    }

    @OnWebSocketMessage
    public void onMessage(Session session, String dataStr) {


        // Save the data
        Tools.dbInit();

        switch(getMessageType(dataStr)) {
            case Reply:
                messageReply(session, dataStr);
                break;
            case Edit:
                messageEdit(session, dataStr);
                break;
        }



        Tools.dbClose();


    }



    public MessageType getMessageType(String someData) {

        try {
        JsonNode rootNode = Tools.JACKSON.readTree(someData);

            Iterator<String> it = rootNode.fieldNames();

            while (it.hasNext()) {
                String nodeName = it.next();
                switch(nodeName) {
                    case "reply" :
                        return MessageType.Reply;
                    case "edit":
                        return MessageType.Edit;
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        return null;
    }

    enum MessageType {
        Edit, Reply;
    }

    public void messageReply(Session session, String replyDataStr) {

        // Get the object
        ReplyData replyData = ReplyData.fromJson(replyDataStr);

        // Collect only works on refetch
        comments = fetchComments();

        // Necessary for comment tree
        Array arr = (Array) comments.collect("breadcrumbs", "id", replyData.getParentId()).get(0);

        List<Long> parentBreadCrumbs = Tools.convertArrayToList(arr);

        SessionScope ss = SessionScope.findBySession(sessionScopes, session);

        Comment newComment = Actions.createComment(ss.getUserObj().getId(),
                1L,
                parentBreadCrumbs,
                replyData.getReply());

        // Fetch the comment threaded view
        CommentThreadedView ctv = COMMENT_THREADED_VIEW.findFirst("id = ?", newComment.getLongId());

        // Add it to the current lazy list
        comments.add(ctv);


        // Convert to a proper commentObj
        CommentObj co = Transformations.convertCommentThreadedView(ctv);


//        comments = fetchComments();

        Set<SessionScope> filteredScopes = SessionScope.constructFilteredScopesFromSessionRequest(sessionScopes, session);

        broadcastMessage(filteredScopes, co.json("reply"));

    }

    public void messageEdit(Session session, String editDataStr) {

        EditData editData = EditData.fromJson(editDataStr);

        Comment c = Actions.editComment(editData.getId(), editData.getEdit());

        // You need to get the breadcrumbs, since there could be many sub comments to this one
        List<CommentBreadcrumbsView> cbvs = COMMENT_BREADCRUMBS_VIEW.where("parent_id = ?", c.getLongId());

        // Convert to a proper commentObj
        CommentObj co = Transformations.convertCommentsToEmbeddedObjects(cbvs).get(0);
        log.info(co.json());

        // Refetch the comments
//        comments = fetchComments();

        // TODO A temp workaround until i find out a more generic way to do this
        CommentThreadedView ctv = COMMENT_THREADED_VIEW.findFirst("id = ?", c.getLongId());

        // Set the comment to its new value
        Integer index = Tools.findIndexByIdInLazyList(comments, c.getLongId());
        comments.set(index, ctv);

        Set<SessionScope> filteredScopes = SessionScope.constructFilteredScopesFromSessionRequest(sessionScopes, session);

        // TODO not sure if this is going to work correctly
        broadcastMessage(filteredScopes, co.json("edit"));

    }

    //Sends a message from one user to all users
    // TODO need to get subsets of sessions based on discussion_id, and parent_id
    // Maybe Map<discussion_id, List<sessions>

    public static void broadcastMessage(Set<SessionScope> filteredScopes, String json) {
        SessionScope.getSessions(filteredScopes).stream().filter(Session::isOpen).forEach(session -> {
            try {
                session.getRemote().sendString(json);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private SessionScope setupSessionScope(Session session) {

        String auth = SessionScope.getAuthFromSession(session);
        Long discussionId = SessionScope.getDiscussionIdFromSession(session);
        Long topParentId = SessionScope.getTopParentIdFromSession(session);

        UserObj userObj;

        if (auth != null) {
            UserLoginView uv = USER_LOGIN_VIEW.findFirst("auth = ?", auth);
            userObj = new UserObj(uv.getLongId(), uv.getString("name"));

        } else {
            User dbUser = Actions.createUser();
            userObj = new UserObj(dbUser.getLongId(), dbUser.getString("name"));
        }

        SessionScope ss = new SessionScope(session, userObj, discussionId, topParentId);
        sessionScopes.add(ss);

        return ss;

    }

    private static LazyList<CommentThreadedView> fetchComments() {
        return COMMENT_THREADED_VIEW.where("discussion_id = ?", 1);
    }


}
